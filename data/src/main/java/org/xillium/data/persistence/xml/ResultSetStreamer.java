package org.xillium.data.persistence.xml;

import java.sql.*;

import java.io.IOException;
import java.io.Writer;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
 
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xillium.base.beans.Strings;
import org.xillium.data.persistence.ParametricQuery;


/**
 * A ParametricQuery.ResultSetWorker implementation that writes the result set as an XML document into a Writer.
 */
public class ResultSetStreamer implements ParametricQuery.ResultSetWorker<Writer> {
    public static final String NAMESPACE = "java://org.xillium.data.persistence.xml";
    public static final String ROOT      = "sql-rs:data";
    public static final String ROW       = "row";
    public static final String COLUMN    = "column";
    public static final String NAME      = "name";
    public static final String TYPE      = "type";

    private final String _name;
    private final Writer _sink;

    public ResultSetStreamer(String name, Writer sink) {
        _name = name;
        _sink = sink;
    }

	@SuppressWarnings("unchecked")
	public Writer process(ResultSet rs) throws SQLException, ParserConfigurationException, TransformerConfigurationException, TransformerException, IOException {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root = doc.createElementNS(NAMESPACE, ROOT);
        Attr attr = doc.createAttribute(NAME);
        attr.setValue(_name);
        root.setAttributeNode(attr);

        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        while (rs.next()) {
            Element row = doc.createElement(ROW);
            for (int i = 0; i < count; ++i) {
                Element col = doc.createElement(COLUMN);
                attr = doc.createAttribute(NAME);
                attr.setValue(Strings.toLowerCamelCase(meta.getColumnName(i+1), '_'));
                col.setAttributeNode(attr);
                //attr = doc.createAttribute(TYPE);
                //attr.setValue(Strings.toLowerCamelCase(meta.getColumnClassName(i+1), '_'));
                //col.setAttributeNode(attr);

                Object value = rs.getObject(i+1);
                if (value != null) {
                    col.appendChild(doc.createTextNode(value.toString()));
                } else {
                    col.appendChild(doc.createTextNode(""));
                }
                row.appendChild(col);
            }
            root.appendChild(row);
        }
        doc.appendChild(root);

        // send the content into the writer
        TransformerFactory.newInstance().newTransformer().transform(new DOMSource(doc), new StreamResult(_sink));
        _sink.flush();

        return _sink;
    }
}
