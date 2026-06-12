package org.koitharu.kotatsu.explore.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.dao.MangaSourcesDao
import org.koitharu.kotatsu.core.db.entity.MangaSourceEntity
import org.koitharu.kotatsu.core.model.MangaSourceInfo
import org.koitharu.kotatsu.core.model.getTitle
import org.koitharu.kotatsu.core.model.isNsfw
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.flattenLatest
import org.koitharu.kotatsu.parsers.model.ContentType
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.util.mapNotNullToSet
import org.koitharu.kotatsu.parsers.util.mapToSet
import java.util.Collections
import java.util.EnumSet
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaSourcesRepository @Inject constructor(
	@LocalizedAppContext private val context: Context,
	private val db: MangaDatabase,
	private val settings: AppSettings,
) {

	private val isNewSourcesAssimilated = AtomicBoolean(false)
	private val dao: MangaSourcesDao
		get() = db.getSourcesDao()

	val allMangaSources: Set<MangaParserSource> = Collections.unmodifiableSet(
		EnumSet.noneOf<MangaParserSource>(MangaParserSource::class.java).also {
			MangaParserSource.entries.filterNotTo(it) { source ->
				source.isBroken
			}
        }
	)

	suspend fun getEnabledSources(): List<MangaSource> {
		assimilateNewSources()
		val order = settings.sourcesSortOrder
		return dao.findAll(!settings.isAllSourcesEnabled, order).toSources(settings.isNsfwContentDisabled, order)
			.let { enabled ->
				val external = getExternalSources()
				val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
				external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
				enabled.filterTo(list) { it.isVisibleInDiscover() }
				list
			}
	}

	suspend fun getPinnedSources(): Set<MangaSource> {
		assimilateNewSources()
		val skipNsfw = settings.isNsfwContentDisabled
		return dao.findAllPinned().mapNotNullToSet {
			it.source.toMangaSourceOrNull()
				?.takeIf { x -> x in allMangaSources }
				?.takeUnless { x -> skipNsfw && x.isNsfw() }
				?.takeUnless { x -> x.isNsfw() }
		}
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		assimilateNewSources()
		return dao.findLastUsed(limit).toSources(settings.isNsfwContentDisabled, null)
			.filter { it.isVisibleInDiscover() }
	}

	suspend fun getDisabledSources(): Set<MangaSource> {
		assimilateNewSources()
		if (settings.isAllSourcesEnabled) {
			return emptySet()
		}
		val result = EnumSet.copyOf(allMangaSources)
		val enabled = dao.findAllEnabledNames()
		for (name in enabled) {
			val source = name.toMangaSourceOrNull() ?: continue
			result.remove(source)
		}
		return result
	}

	suspend fun queryParserSources(
		isDisabledOnly: Boolean,
		isNewOnly: Boolean,
		excludeBroken: Boolean,
		types: Set<ContentType>,
		query: String?,
		locale: String?,
		sortOrder: SourcesSortOrder?,
		skipNsfwSources: Boolean = settings.isNsfwContentDisabled,
	): List<MangaParserSource> {
		assimilateNewSources()
		val entities = dao.findAll().toMutableList()
		if (isDisabledOnly && !settings.isAllSourcesEnabled) {
			entities.removeAll { it.isEnabled }
		}
		if (isNewOnly) {
			entities.retainAll { it.addedIn == BuildConfig.VERSION_CODE }
		}
		val sources = entities.toSources(
			skipNsfwSources = skipNsfwSources,
			sortOrder = sortOrder,
		).run {
			mapNotNullTo(ArrayList(size)) { it.mangaSource as? MangaParserSource }
		}
		if (locale != null) {
			sources.retainAll { it.locale == locale }
		}
		if (excludeBroken) {
			sources.removeAll { it.isBroken }
		}
		if (types.isNotEmpty()) {
			sources.retainAll { it.contentType in types }
		}
		if (!query.isNullOrEmpty()) {
			sources.retainAll {
				it.getTitle(context).contains(query, ignoreCase = true) || it.name.contains(query, ignoreCase = true)
			}
		}
		return sources
	}

	fun observeIsEnabled(source: MangaSource): Flow<Boolean> {
		return dao.observeIsEnabled(source.name).onStart { assimilateNewSources() }
	}

	fun observeEnabledSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, sources ->
			sources.count {
				it.source.toMangaSourceOrNull()
					?.let { s -> s in allMangaSources && (!skipNsfw || !s.isNsfw()) } == true
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeAvailableSourcesCount(): Flow<Int> {
		return combine(
			observeIsNsfwDisabled(),
			observeAllEnabled().flatMapLatest { isAllSourcesEnabled ->
				dao.observeAll(!isAllSourcesEnabled, SourcesSortOrder.MANUAL)
			},
		) { skipNsfw, enabledSources ->
			val enabled = enabledSources.mapToSet { it.source }
			allMangaSources.count { x ->
				x.name !in enabled && (!skipNsfw || !x.isNsfw())
			}
		}.distinctUntilChanged().onStart { assimilateNewSources() }
	}

	fun observeEnabledSources(): Flow<List<MangaSourceInfo>> = combine(
		observeIsNsfwDisabled(),
		observeAllEnabled(),
		observeSortOrder(),
	) { skipNsfw, allEnabled, order ->
		dao.observeAll(!allEnabled, order).map {
			it.toSources(skipNsfw, order)
		}
	}.flattenLatest()
		.onStart { assimilateNewSources() }
		.combine(observeExternalSources()) { enabled, external ->
			val list = ArrayList<MangaSourceInfo>(enabled.size + external.size)
			external.mapTo(list) { MangaSourceInfo(it, isEnabled = true, isPinned = true) }
			enabled.filterTo(list) { it.isVisibleInDiscover() }
			list
		}

	fun observeAll(): Flow<List<Pair<MangaSource, Boolean>>> = dao.observeAll().map { entities ->
		val result = ArrayList<Pair<MangaSource, Boolean>>(entities.size)
		for (entity in entities) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (source in allMangaSources) {
				result.add(source to entity.isEnabled)
			}
		}
		result
	}.onStart { assimilateNewSources() }

	fun observeEnglishSourceHealth(): Flow<List<MangaSourceHealthInfo>> = dao.observeAll().map { entities ->
		val entityMap = entities.associateBy { it.source }
		MangaParserSource.entries
			.asSequence()
			.filter { it.locale == "en" }
			.map { source ->
				val entity = entityMap[source.name]
				MangaSourceHealthInfo(
					source = source,
					isEnabled = settings.isAllSourcesEnabled || entity?.isEnabled == true,
					isPinned = entity?.isPinned == true,
					lastUsedAt = entity?.lastUsedAt ?: 0L,
					cfState = entity?.cfState ?: CloudFlareHelper.PROTECTION_NOT_DETECTED,
				)
			}
			.sortedBy { it.source.getTitle(context) }
			.toList()
	}.onStart { assimilateNewSources() }

	suspend fun setSourcesEnabled(sources: Collection<MangaSource>, isEnabled: Boolean): ReversibleHandle {
		setSourcesEnabledImpl(sources, isEnabled)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isEnabled)
		}
	}

	suspend fun setSourcesEnabledExclusive(sources: Set<MangaSource>) {
		db.withTransaction {
			assimilateNewSources()
			for (s in allMangaSources) {
				dao.setEnabled(s.name, s in sources)
			}
		}
	}

	suspend fun disableAllSources() {
		db.withTransaction {
			assimilateNewSources()
			dao.disableAllSources()
		}
	}

	suspend fun setPositions(sources: List<MangaSource>) {
		db.withTransaction {
			for ((index, item) in sources.withIndex()) {
				dao.setSortKey(item.name, index)
			}
		}
	}

	fun observeHasNewSources(): Flow<Boolean> = observeIsNsfwDisabled().map { skipNsfw ->
		val sources = dao.findAllFromVersion(BuildConfig.VERSION_CODE).toSources(skipNsfw, null)
		sources.isNotEmpty() && sources.size != allMangaSources.size
	}.onStart { assimilateNewSources() }

	fun observeHasNewSourcesForBadge(): Flow<Boolean> = combine(
		settings.observeAsFlow(AppSettings.KEY_SOURCES_VERSION) { sourcesVersion },
		observeIsNsfwDisabled(),
	) { version, skipNsfw ->
		if (version < BuildConfig.VERSION_CODE) {
			val sources = dao.findAllFromVersion(version).toSources(skipNsfw, null)
			sources.isNotEmpty()
		} else {
			false
		}
	}.onStart { assimilateNewSources() }

	fun clearNewSourcesBadge() {
		settings.sourcesVersion = BuildConfig.VERSION_CODE
	}

	private suspend fun assimilateNewSources(): Boolean {
		if (isNewSourcesAssimilated.getAndSet(true)) {
			return false
		}
		val new = getNewSources()
		if (new.isEmpty()) {
			return false
		}
		var maxSortKey = dao.getMaxSortKey()
		val isAllEnabled = settings.isAllSourcesEnabled
		val entities = new.map { x ->
			MangaSourceEntity(
				source = x.name,
				isEnabled = isAllEnabled || x.shouldEnableByDefault(),
				sortKey = ++maxSortKey,
				addedIn = BuildConfig.VERSION_CODE,
				lastUsedAt = 0,
				isPinned = false,
				cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
			)
		}
		dao.insertIfAbsent(entities)
		return true
	}

	suspend fun isSetupRequired(): Boolean {
		return settings.sourcesVersion == 0 && dao.findAllEnabledNames().isEmpty()
	}

	suspend fun setIsPinned(sources: Collection<MangaSource>, isPinned: Boolean): ReversibleHandle {
		setSourcesPinnedImpl(sources, isPinned)
		return ReversibleHandle {
			setSourcesEnabledImpl(sources, !isPinned)
		}
	}

	suspend fun trackUsage(source: MangaSource) {
		if (!settings.isIncognitoModeEnabled(source.isNsfw())) {
			dao.setLastUsed(source.name, System.currentTimeMillis())
		}
	}

	private suspend fun setSourcesEnabledImpl(sources: Collection<MangaSource>, isEnabled: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setEnabled(sources.first().name, isEnabled)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setEnabled(source.name, isEnabled)
			}
		}
	}

	private suspend fun getNewSources(): MutableSet<out MangaSource> {
		val entities = dao.findAll()
		val result = EnumSet.copyOf(allMangaSources)
		for (e in entities) {
			result.remove(e.source.toMangaSourceOrNull() ?: continue)
		}
		return result
	}

	private fun MangaSource.shouldEnableByDefault(): Boolean {
		val source = this as? MangaParserSource ?: return false
		return !source.isNsfw() && source.locale == "en"
	}

	private fun MangaSourceInfo.isVisibleInDiscover(): Boolean {
		return !mangaSource.isNsfw()
	}

	private suspend fun setSourcesPinnedImpl(sources: Collection<MangaSource>, isPinned: Boolean) {
		if (sources.size == 1) { // fast path
			dao.setPinned(sources.first().name, isPinned)
			return
		}
		db.withTransaction {
			for (source in sources) {
				dao.setPinned(source.name, isPinned)
			}
		}
	}

	private fun observeExternalSources(): Flow<List<ExternalMangaSource>> {
		return callbackFlow {
			val receiver = object : BroadcastReceiver() {
				override fun onReceive(context: Context?, intent: Intent?) {
					trySendBlocking(intent)
				}
			}
			ContextCompat.registerReceiver(
				context,
				receiver,
				IntentFilter().apply {
					addAction(Intent.ACTION_PACKAGE_ADDED)
					addAction(Intent.ACTION_PACKAGE_VERIFIED)
					addAction(Intent.ACTION_PACKAGE_REPLACED)
					addAction(Intent.ACTION_PACKAGE_REMOVED)
					addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
					addDataScheme("package")
				},
				ContextCompat.RECEIVER_EXPORTED,
			)
			awaitClose { context.unregisterReceiver(receiver) }
		}.onStart {
			emit(null)
		}.map {
			getExternalSources()
		}.distinctUntilChanged()
			.conflate()
	}

	fun getExternalSources(): List<ExternalMangaSource> = context.packageManager.queryIntentContentProviders(
		Intent("app.kotatsu.parser.PROVIDE_MANGA"), 0,
	).map { resolveInfo ->
		ExternalMangaSource(
			packageName = resolveInfo.providerInfo.packageName,
			authority = resolveInfo.providerInfo.authority,
		)
	}

	private fun List<MangaSourceEntity>.toSources(
		skipNsfwSources: Boolean,
		sortOrder: SourcesSortOrder?,
	): MutableList<MangaSourceInfo> {
		val isAllEnabled = settings.isAllSourcesEnabled
		val result = ArrayList<MangaSourceInfo>(size)
		for (entity in this) {
			val source = entity.source.toMangaSourceOrNull() ?: continue
			if (skipNsfwSources && source.isNsfw()) {
				continue
			}
			if (source in allMangaSources) {
				result.add(
					MangaSourceInfo(
						mangaSource = source,
						isEnabled = entity.isEnabled || isAllEnabled,
						isPinned = entity.isPinned,
					),
				)
			}
		}
		if (sortOrder == SourcesSortOrder.ALPHABETIC) {
			result.sortWith(compareBy<MangaSourceInfo> { !it.isPinned }.thenBy { it.getTitle(context) })
		}
		return result
	}

	private fun observeIsNsfwDisabled() = settings.observeAsFlow(AppSettings.KEY_DISABLE_NSFW) {
		isNsfwContentDisabled
	}

	private fun observeSortOrder() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ORDER) {
		sourcesSortOrder
	}

	private fun observeAllEnabled() = settings.observeAsFlow(AppSettings.KEY_SOURCES_ENABLED_ALL) {
		isAllSourcesEnabled
	}

	private fun String.toMangaSourceOrNull(): MangaParserSource? = MangaParserSource.entries.find { it.name == this }

	private fun MangaParserSource.isTarumiHidden(): Boolean {
		val normalizedName = name.normalizedSourceKey()
		val normalizedTitle = title.normalizedSourceKey()
		return normalizedName in HIDDEN_SOURCE_NAMES ||
			normalizedName.startsWith("weebdex") ||
			normalizedTitle in HIDDEN_SOURCE_TITLES ||
			HIDDEN_SOURCE_PATTERNS.any { pattern ->
				normalizedName.contains(pattern) || normalizedTitle.contains(pattern)
			}
	}

	private fun String.normalizedSourceKey(): String = lowercase().filter(Char::isLetterOrDigit)

	private companion object {

		val HIDDEN_SOURCE_NAMES = setOf(
			"holoearth",
			"mangareaderto",
			"manhuaga",
			"neatmanga",
			"manga1st",
			"resetscans",
			"zinchanmanganet",
			"asurascansus",
			"bookmanga",
			"shootingstarscans",
			"mangafoxfull",
			"mangatyrant",
			"coffeemanga",
			"zinchanmanga",
			"sitemanga",
			"mangamanhua",
			"mangakiss",
			"zandynofansub",
			"firstkissmanhua",
			"babelwuxia",
			"inkreads",
			"mangarolls",
			"mangatxgg",
			"manhuauss",
			"arvenscans",
			"readerevilflowers",
			"dragontea",
			"freemanga",
			"itsyourightmanhua",
			"gourmetscans",
			"manhuaes",
			"manhwamanhua",
			"mangaeclipse",
			"asurascansgg",
			"mangareadco",
			"woopread",
			"adultwebtoon",
			"batcave",
			"comicsvalley",
			"comiz",
			"dexhentai",
			"ehentaimanga",
			"hentai20",
			"hentaimanga",
			"hentaiwebtoon",
			"hipertoon",
			"lunarscanorg",
			"mangahentai",
			"manhwa18org",
			"manhwahentai",
			"manhwahentaito",
			"manhwarawcom",
			"manhwax",
			"manhwasmen",
			"manytoon",
			"manytoonme",
			"milftoon",
			"porncomixonline",
			"summanga",
			"toonilyme",
			"toonizy",
			"webtoonscan",
			"yaoihub",
		)

		val HIDDEN_SOURCE_TITLES = setOf(
			"holoearth",
			"weebdex",
			"mangareaderto",
			"paragonscans",
			"manhuaga",
			"neatmanga",
			"manga1st",
			"resetscans",
			"zinchanmanganet",
			"asurascanus",
			"bookmanga",
			"shootingstarscans",
			"mangafoxfull",
			"mangatyrant",
			"coffeemanga",
			"zinchanmangamobi",
			"sitemanga",
			"mangamanhua",
			"mangakiss",
			"zandynofansub",
			"firstkissmanhua",
			"babelwuxia",
			"inkreads",
			"mangarolls",
			"mangatxgg",
			"manhuauss",
			"arvencomics",
			"evilflowers",
			"dragontea",
			"freemanga",
			"itsyourightmanhua",
			"gourmetscans",
			"manhuaes",
			"manhwamanhua",
			"mangaeclipse",
			"asurascansgg",
			"mangareadco",
			"woopread",
			"adultwebtoon",
			"batcave",
			"comicsvalley",
			"comiz",
			"dexhentai",
			"ehentaimanga",
			"hentai20",
			"hentaimanga",
			"hentaiwebtoon",
			"hipertoon",
			"lunarscanorg",
			"mangahentai",
			"manhwa18org",
			"manhwahentai",
			"manhwahentaito",
			"manhwarawcom",
			"manhwax",
			"manhwasmen",
			"manytoon",
			"manytoonme",
			"milftoon",
			"porncomixonline",
			"summanga",
			"toonilyme",
			"toonizy",
			"webtoonscan",
			"yaoihub",
		)

		val HIDDEN_SOURCE_PATTERNS = setOf(
			"holoearth",
			"weebdex",
			"mangareaderto",
			"paragonscans",
			"manhuaga",
			"neatmanga",
			"manga1st",
			"resetscans",
			"zinchanmanga",
			"asurascansus",
			"asurascanus",
			"bookmanga",
			"shootingstarscans",
			"mangafoxfull",
			"mangatyrant",
			"coffeemanga",
			"sitemanga",
			"mangamanhua",
			"mangakiss",
			"zandynofansub",
			"firstkissmanhua",
			"babelwuxia",
			"inkreads",
			"mangarolls",
			"mangatxgg",
			"manhuauss",
			"arvencomics",
			"arvenscans",
			"evilflowers",
			"dragontea",
			"freemanga",
			"itsyourightmanhua",
			"gourmetscans",
			"manhuaes",
			"manhwamanhua",
			"mangaeclipse",
			"asurascansgg",
			"mangareadco",
			"woopread",
			"adultwebtoon",
			"batcave",
			"comicsvalley",
			"comiz",
			"dexhentai",
			"ehentaimanga",
			"hentai20",
			"hentaimanga",
			"hentaiwebtoon",
			"hipertoon",
			"lunarscan",
			"mangahentai",
			"manhwa18",
			"manhwahentai",
			"manhwaraw",
			"manhwax",
			"manhwasmen",
			"manytoon",
			"milftoon",
			"porncomix",
			"summanga",
			"toonily",
			"toonizy",
			"webtoonscan",
			"yaoihub",
		)
	}
}

data class MangaSourceHealthInfo(
	val source: MangaParserSource,
	val isEnabled: Boolean,
	val isPinned: Boolean,
	val lastUsedAt: Long,
	val cfState: Int,
)
