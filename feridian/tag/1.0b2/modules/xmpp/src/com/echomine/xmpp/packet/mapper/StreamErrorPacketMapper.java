package com.echomine.xmpp.packet.mapper;

import java.io.IOException;

import org.jibx.runtime.IMarshallingContext;
import org.jibx.runtime.IUnmarshallingContext;
import org.jibx.runtime.IXMLReader;
import org.jibx.runtime.JiBXException;
import org.jibx.runtime.impl.MarshallingContext;
import org.jibx.runtime.impl.UnmarshallingContext;

import com.echomine.jibx.XMPPStreamWriter;
import com.echomine.util.LocaleUtil;
import com.echomine.xmpp.NSI;
import com.echomine.xmpp.XMPPConstants;
import com.echomine.xmpp.packet.ErrorPacket;

public class StreamErrorPacketMapper extends AbstractPacketMapper implements
        XMPPConstants {
    protected static final String TEXT_ELEMENT_NAME = "text";
    protected static final String ERROR_ELEMENT_NAME = "error";
    protected static final String ERROR_LANG_ATTRIBUTE_NAME = "lang";

    public StreamErrorPacketMapper(String uri, int index, String name) {
        super(uri, index, name);
    }

    /**
     * marshalls the data into an xml string
     */
    public void marshal(Object obj, IMarshallingContext ictx) throws JiBXException {
        // make sure the parameters are as expected
        if (!(obj instanceof ErrorPacket)) {
            throw new JiBXException("Invalid object type for marshaller");
        } else if (!(ictx instanceof MarshallingContext)) {
            throw new JiBXException("Invalid object type for marshaller");
        } else {
            // start by generating start tag for container
            MarshallingContext ctx = (MarshallingContext) ictx;
            ErrorPacket packet = (ErrorPacket) obj;
            XMPPStreamWriter writer = (XMPPStreamWriter) ctx.getXmlWriter();
            int idx = writer.getNamespaceCount();
            // add extension namespaces
            String[] extns;
            if (packet.getApplicationCondition() == null)
                extns = new String[] { NS_STREAMS_ERROR };
            else
                extns = new String[] { NS_STREAMS_ERROR,
                        packet.getApplicationCondition().getNamespaceURI() };
            writer.pushExtensionNamespaces(extns);
            try {
                writer.startStreamTagOpen(name);
                writer.closeStartTag();
                marshallErrorCondition(ctx, idx, idx + 1, packet);
                // close error tag
                writer.endStreamTag(name);
                writer.flush();
                writer.popExtensionNamespaces();
            } catch (IOException ex) {
                throw new JiBXException("Error flushing stream", ex);
            }
        }
    }

    /**
     * Marshalls the inner error conditions, text, and application conditions.
     * 
     * @param ctx the marshalling context
     * @param idx the namespace index to use for error conditions
     * @param appIdx the namespace index to use for application condition
     * @param packet the error packet containing the data
     * @throws JiBXException
     */
    protected void marshallErrorCondition(MarshallingContext ctx, int idx, int appIdx, ErrorPacket packet) throws JiBXException {
        // write out defined condition
        if (packet.getCondition() == null)
            throw new JiBXException("Error packet must contain a condition");
        ctx.startTagNamespaces(idx, packet.getCondition(), new int[] { idx }, new String[] { "" }).closeStartEmpty();
        // write out the error descriptive text
        if (packet.getText() != null) {
            ctx.startTagNamespaces(idx, TEXT_ELEMENT_NAME, new int[] { idx }, new String[] { "" });
            if (packet.getTextLocale() != null)
                ctx.attribute(IDX_XML, ERROR_LANG_ATTRIBUTE_NAME, LocaleUtil.format(packet.getTextLocale()));
            ctx.closeStartContent();
            ctx.content(packet.getText());
            ctx.endTag(idx, TEXT_ELEMENT_NAME);
        }
        // write out application specific error
        if (packet.getApplicationCondition() != null) {
            NSI nsi = packet.getApplicationCondition();
            ctx.startTagNamespaces(appIdx, nsi.getName(), new int[] { appIdx }, new String[] { "" }).closeStartEmpty();
        }
    }

    /**
     * Unmarshalls the error packet. The reason for this is that the error
     * packet uses different condition elements and may also condition
     * application-specific conditions. Due to the highly non-conforming nature
     * of the error message, a custom mapper is required.
     */
    public Object unmarshal(Object obj, IUnmarshallingContext ictx) throws JiBXException {
        // make sure we're at the right start tag
        UnmarshallingContext ctx = (UnmarshallingContext) ictx;
        if (!ctx.isAt(uri, name)) {
            ctx.throwStartTagNameError(uri, name);
        }
        ErrorPacket packet = (ErrorPacket) obj;
        if (packet == null)
            packet = new ErrorPacket();
        // parse past the error element
        ctx.parsePastStartTag(uri, name);
        unmarshallErrorCondition(ctx, NS_STREAMS_ERROR, packet);
        ctx.toEnd();
        return packet;
    }

    /**
     * does the main unmarshalling of the elements.
     * 
     * @param packet the packet that contains the data
     */
    protected void unmarshallErrorCondition(UnmarshallingContext ctx, String errorNs, ErrorPacket packet) throws JiBXException {
        // set our error condition
        packet.setCondition(ctx.toStart());
        // find optional error text
        ctx.parsePastEndTag(ctx.getNamespace(), ctx.getName());
        int eventType = ctx.toTag();
        if (eventType == IXMLReader.START_TAG
                && TEXT_ELEMENT_NAME.equals(ctx.getName())) {
            if (ctx.hasAttribute(NS_XML, ERROR_LANG_ATTRIBUTE_NAME))
                packet.setTextLocale(LocaleUtil.parseLocale(ctx.attributeText(NS_XML, ERROR_LANG_ATTRIBUTE_NAME)));
            packet.setText(ctx.parseElementText(errorNs, TEXT_ELEMENT_NAME));
            eventType = ctx.toTag();
        }
        if (eventType == IXMLReader.START_TAG) {
            // optional application specific condition
            packet.setApplicationCondition(new NSI(ctx.getName(), ctx.getNamespace()));
            // parse to end of the error element
            ctx.parsePastEndTag(ctx.getNamespace(), ctx.getName());
        }
    }
}
