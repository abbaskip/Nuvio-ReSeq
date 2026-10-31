package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HomeCatalogSignatureTest {
    private val addon = Addon("test", "Test", version = "1", description = null, logo = null,
        baseUrl = "https://example.com", types = listOf(ContentType.SERIES),
        resources = listOf(AddonResource("catalog", listOf("series"), listOf("tt"))),
        catalogs = (1..100).map { CatalogDescriptor(ContentType.SERIES, id = "c$it", name = "Catalog $it",
            extra = listOf(CatalogExtra("genre", options = listOf("Drama", "Comedy"))), showInHome = true) })

    @Test fun `optimized signature preserves every legacy field and ordering rule`() {
        val disabled = setOf("c4", "c2")
        val variants = listOf(addon, addon.copy(version = "2"), addon.copy(configVersion = 3),
            addon.copy(manifestLanguage = "en"), addon.copy(catalogs = addon.catalogs.reversed()),
            addon.copy(resources = addon.resources + AddonResource("meta", listOf("series"), null)))
        variants.forEach { assertEquals(legacySignature(listOf(it), disabled), buildHomeCatalogLoadSignature(listOf(it), disabled)) }
        assertEquals(legacySignature(variants, disabled), buildHomeCatalogLoadSignature(variants, disabled))
        assertEquals(legacySignature(emptyList(), emptySet()), buildHomeCatalogLoadSignature(emptyList(), emptySet()))
        assertNotEquals(buildHomeCatalogLoadSignature(listOf(addon), disabled), buildHomeCatalogLoadSignature(listOf(addon.copy(version = "2")), disabled))
    }
}

private fun legacySignature(addons: List<Addon>, disabledHomeCatalogKeys: Set<String>): String {
    val addonCatalogSignature = addons
        .flatMap { addon ->
            addon.catalogs.map { catalog ->
                val extraSignature = catalog.extra.joinToString(";") { extra ->
                    listOf(
                        extra.name,
                        extra.isRequired.toString(),
                        extra.options.orEmpty().joinToString("|"),
                        extra.defaultValue.orEmpty(),
                        extra.optionsLimit?.toString().orEmpty()
                    ).joinToString(":")
                }
                listOf(
                    addon.id,
                    addon.baseUrl,
                    addon.version,
                    addon.configVersion?.toString().orEmpty(),
                    addon.manifestLanguage.orEmpty(),
                    addon.rawTypes.joinToString("|"),
                    addon.resources.joinToString("|") { resource ->
                        listOf(
                            resource.name,
                            resource.types.joinToString("/"),
                            resource.idPrefixes.orEmpty().joinToString("/")
                        ).joinToString(":")
                    },
                    addon.idPrefixes.joinToString("|"),
                    catalog.apiType,
                    catalog.id,
                    catalog.name,
                    catalog.showInHome.toString(),
                    catalog.hasExplicitShowInHome.toString(),
                    catalog.pageSize?.toString().orEmpty(),
                    catalog.extraSupported.joinToString("|"),
                    catalog.extraRequired.joinToString("|"),
                    extraSignature
                ).joinToString("|")
            }
        }
        .sorted()
        .joinToString(separator = ",")
    val disabledSignature = disabledHomeCatalogKeys
        .asSequence()
        .sorted()
        .joinToString(separator = ",")
    return "$addonCatalogSignature::$disabledSignature"
}
