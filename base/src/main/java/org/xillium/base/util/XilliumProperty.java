package org.xillium.base.util;


/**
 * A property to be inserted into a Properties by the assembler.
 */
public class XilliumProperty {
    final String key, value;

    /**
     * Constructs a XilliumProperty from a key and a value.
     */
    public XilliumProperty(String k, String v) {
        key = k;
        value = v;
    }
}
