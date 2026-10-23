package com.patchscratch.novelnotifier

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl

/**
 * EPUB PAGED VIEW (v3.1): Readium's navigator renders the publication as
 * real horizontal pages (ViewPager) with its own web-renderer — a true
 * epub reading surface, not the app's scroll extraction view. Shown as a
 * third reader mode for local-library chapters (Wayne, 2026-10-22:
 * "shouldn't it be running an epub reader?").
 *
 * The fragment is created once per composition; chapter changes after
 * that go through [goToHref] (the fragment navigates without recreating).
 */
@Composable
fun EpubPagedView(
    publication: Publication,
    initialHref: String,
    readerTheme: String,
    serif: Boolean,
    fontSizeSp: Int = 17,
    // Page-accurate resume (v3.2b): the last saved locator for this book
    // (JSON); used when its href matches the chapter being opened.
    initialLocatorJson: String? = null,
    // Fires with the current locator JSON as the reader moves — the caller
    // persists it per book.
    onLocator: (String) -> Unit = {},
    onExternalLink: (String) -> Unit
) {
    val fragmentRef = remember { arrayOf<EpubNavigatorFragment?>(null) }
    // Non-null locator for the chapter — the composition's anchor. A saved
    // locator for THIS chapter restores page-accurate position.
    val locator = remember(initialHref, initialLocatorJson) {
        savedLocatorOf(initialLocatorJson, initialHref)
            ?: locatorOf(publication, initialHref)
            ?: publication.readingOrder.firstOrNull()?.let { link ->
                org.readium.r2.shared.util.Url(link.href.toString())?.let { url ->
                    Locator(
                        href = url,
                        mediaType = link.mediaType
                            ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
                        title = link.title, locations = Locator.Locations(), text = Locator.Text()
                    )
                }
            }
    } ?: return   // no locator resolvable — nothing to render

    // Position reporting: the navigator's locator flow is collected and
    // forwarded as JSON for the caller to persist.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)   // let the fragment attach first
        val frag = fragmentRef[0] ?: return@LaunchedEffect
        runCatching {
            frag.currentLocator.collect { loc ->
                runCatching { loc.toJSON().toString() }.getOrNull()?.let(onLocator)
            }
        }
    }

    // Chapter navigation after the first composition.
    LaunchedEffect(initialHref) {
        fragmentRef[0]?.let { frag ->
            locatorOf(publication, initialHref)?.let { frag.go(it, false) }
        }
    }

    // v3.2b: live preference updates — theme/font/size changes while the
    // paged view is up submit straight to the navigator, no rebuild.
    LaunchedEffect(readerTheme, serif, fontSizeSp) {
        fragmentRef[0]?.submitPreferences(
            readiumPreferences(readerTheme, serif, fontSizeSp)
        )
    }

    AndroidView(
        modifier = Modifier,
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = View.generateViewId()
                // The transaction must wait for ATTACH — committing in the
                // factory means the container isn't in the view tree yet
                // and the FragmentManager can't resolve its id ("No view
                // found for id 0x1", live-caught on the rig).
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    var committed = false
                    override fun onViewAttachedToWindow(v: View) {
                        if (committed) return
                        committed = true
                        val fm = (v.context as? FragmentActivity)?.supportFragmentManager
                            ?: return
                        if (fm.findFragmentById(id) != null) return   // restored
                        val navFactory = EpubNavigatorFactory(
                            publication,
                            EpubNavigatorFactory.Configuration(EpubDefaults())
                        )
                        fm.fragmentFactory = navFactory.createFragmentFactory(
                            initialLocator = locator,
                            readingOrder = publication.readingOrder,
                            initialPreferences = readiumPreferences(readerTheme, serif, fontSizeSp),
                            listener = object : EpubNavigatorFragment.Listener {
                                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                                    onExternalLink(url.toString())
                                }
                            },
                            paginationListener = null,
                            configuration = EpubNavigatorFragment.Configuration()
                        )
                        fm.beginTransaction()
                            .replace(id, EpubNavigatorFragment::class.java, null)
                            .commitAllowingStateLoss()
                        v.post {
                            fragmentRef[0] = fm.findFragmentById(id) as? EpubNavigatorFragment
                        }
                    }
                    override fun onViewDetachedFromWindow(v: View) {}
                })
            }
        }
    )
}

/** The saved locator when it points at THIS chapter; null otherwise. */
private fun savedLocatorOf(json: String?, href: String): Locator? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val loc = Locator.fromJSON(org.json.JSONObject(json)) ?: return null
        if (loc.href.toString() != href && loc.href.toString() != href.substringBefore("#")) null
        else loc
    }.getOrNull()
}

/** The chapter's Locator from its href (first spine/ToC match). */
private fun locatorOf(publication: Publication, href: String): Locator? {
    val link = publication.readingOrder.firstOrNull { it.href.toString() == href }
        ?: publication.readingOrder.firstOrNull { it.href.toString() == href.substringBefore("#") }
        ?: return null
    val url = org.readium.r2.shared.util.Url(link.href.toString()) ?: return null
    return Locator(
        href = url,
        mediaType = link.mediaType ?: org.readium.r2.shared.util.mediatype.MediaType.XHTML,
        title = link.title, locations = Locator.Locations(), text = Locator.Text()
    )
}

/**
 * Map the app's reader settings onto Readium's preference set. Font size:
 * the app's sp (12..28, default 17) becomes Readium's relative scale
 * (1.0 = publisher default) — 17sp maps to 1.0, each step ±~6%.
 */
private fun readiumPreferences(readerTheme: String, serif: Boolean, fontSizeSp: Int): EpubPreferences =
    EpubPreferences(
        theme = when (readerTheme) {
            "dark" -> Theme.DARK
            "sepia" -> Theme.SEPIA
            else -> Theme.LIGHT
        },
        fontFamily = if (serif) org.readium.r2.navigator.preferences.FontFamily("serif")
        else org.readium.r2.navigator.preferences.FontFamily("sans-serif"),
        fontSize = fontSizeSp / 17.0,
        scroll = false   // paged — the whole point
    )
