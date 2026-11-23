package com.nuvio.tv.mobile

import okhttp3.HttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/** Resolve inherited DASH addressing before giving the receiver scoped segment URLs. */
internal fun rewriteCastDash(xml: String, manifest: HttpUrl, fixed: (HttpUrl) -> String,
    template: (HttpUrl, String) -> String): String {
    require(!xml.contains("<!DOCTYPE", true))
    val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(InputSource(StringReader(xml)))
    fun Element.children() = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
    fun visit(node: Element, parentUrl: HttpUrl, inherited: List<Element>) {
        val children = node.children()
        val bases = children.filter { it.localName == "BaseURL" }
        val base = bases.firstOrNull()?.let { requireNotNull(parentUrl.resolve(it.textContent.trim())) } ?: parentUrl
        bases.forEach(node::removeChild)
        val addressing = children.filter { it.localName in setOf("SegmentTemplate", "SegmentList", "SegmentBase") }
        val chain = inherited + addressing
        if (node.localName == "Representation") {
            val kind = chain.lastOrNull()?.localName
            val all = chain.filter { it.localName == kind }
            val current = all.lastOrNull()?.cloneNode(true) as? Element
            if (current != null) {
                for (ancestor in all.asReversed().drop(1)) {
                    for (i in 0 until ancestor.attributes.length) {
                        val attr = ancestor.attributes.item(i)
                        if (!current.hasAttribute(attr.nodeName)) current.setAttribute(attr.nodeName, attr.nodeValue)
                    }
                    val own = current.children().map { it.localName }.toSet()
                    ancestor.children().filterNot { it.localName in own }.forEach { current.appendChild(it.cloneNode(true)) }
                }
                fun rewrite(entry: Element) {
                    for (attribute in listOf("media", "index", "initialization", "sourceURL")) {
                        if (!entry.hasAttribute(attribute)) continue
                        val original = entry.getAttribute(attribute)
                        val value = original.replace("\$RepresentationID\$", node.getAttribute("id"))
                            .replace("\$Bandwidth\$", node.getAttribute("bandwidth"))
                        entry.setAttribute(attribute, if (entry.localName == "SegmentTemplate") template(base, value)
                            else fixed(requireNotNull(base.resolve(value))))
                    }
                    entry.children().forEach(::rewrite)
                }
                rewrite(current)
                node.appendChild(current)
            }
            if (kind != "SegmentTemplate" || bases.isNotEmpty()) {
                node.insertBefore(document.createElementNS(node.namespaceURI, "BaseURL").apply { textContent = fixed(base) }, node.firstChild)
            }
        } else {
            children.filter { it.localName in setOf("Period", "AdaptationSet", "Representation") }.forEach { visit(it, base, chain) }
        }
        addressing.forEach(node::removeChild)
    }
    visit(document.documentElement, manifest, emptyList())
    return StringWriter().also { TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it)) }.toString()
}
