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
    onExternalLink: (String) -> Unit
) {
    val fragmentRef = remember { arrayOf<EpubNavigatorFragment?>(null) }
    // Non-null locator for the chapter — the composition's anchor.
    val locator = remember(initialHref) {
        locatorOf(publication, initialHref)
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

    // Chapter navigation after the first composition.
    LaunchedEffect(initialHref) {
        fragmentRef[0]?.let { frag ->
            locatorOf(publication, initialHref)?.let { frag.go(it, false) }
        }
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
                            initialPreferences = readiumPreferences(readerTheme, serif),
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

/** Map the app's reader settings onto Readium's preference set. */
private fun readiumPreferences(readerTheme: String, serif: Boolean): EpubPreferences =
    EpubPreferences(
        theme = when (readerTheme) {
            "dark" -> Theme.DARK
            "sepia" -> Theme.SEPIA
            else -> Theme.LIGHT
        },
        fontFamily = if (serif) org.readium.r2.navigator.preferences.FontFamily("serif")
        else org.readium.r2.navigator.preferences.FontFamily("sans-serif"),
        scroll = false   // paged — the whole point
    )
