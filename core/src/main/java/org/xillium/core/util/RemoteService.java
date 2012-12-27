package org.xillium.core.util;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;
import org.xillium.base.etc.Arrays;
import org.xillium.data.DataObject;
import org.xillium.data.DataBinder;
import org.xillium.data.CachedResultSet;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * An interface to a remote Xillium service.
 */
public class RemoteService {
    private static final Logger _logger = Logger.getLogger(RemoteService.class.getName());
    private static final ObjectMapper _mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    /**
     * This class represents a response from a remote Xillium service.
     */
    public static class Response {
        public Map<String, Object> params;
        public Map<String, CachedResultSet> tables;
        public transient byte[] body;

        Response setResponseBody(byte[] body) {
            this.body = body;
            return this;
        }
    }

    /**
     * Calls a remote service with values in the given DataObject as arguments.
     */
	public static Response call(String server, String service, DataObject data) {
        List<String> params = new ArrayList<String>();
        for (Field field: data.getClass().getFields()) {
            field.setAccessible(true);
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            try {
                Object value = field.get(data);
                if (value == null) value = "";
                params.add(field.getName() + '=' + value);
            } catch (IllegalAccessException x) {}
        }
        return call(server, service, params.toArray(new String[params.size()]));
    }

    /**
     * Calls a remote service with parameters in the given DataBinder as arguments.
     */
	public static Response call(String server, String service, DataBinder binder) {
        List<String> params = new ArrayList<String>();
        for (Map.Entry<String, String> entry: binder.entrySet()) {
            String name = entry.getKey();
            if (name.charAt(0) == '_' || name.charAt(0) == '#') continue;
            params.add(name + '=' + entry.getValue());
        }
        return call(server, service, params.toArray(new String[params.size()]));
    }

    /**
     * Calls a remote service with a list of "name=value" string values as arguments.
     */
	public static Response call(String server, String service, String... params) {
		try {
            URL url = new URL(server + '/' + service);
			//System.err.println("Calling " + url);
			URLConnection connection = url.openConnection();
			connection.setDoOutput(true);
			PrintWriter pw = new PrintWriter(connection.getOutputStream());
			for (String param: params) {
                _logger.fine(param);
				pw.print(param); pw.print('&');
			}
			pw.close();
            InputStream in = connection.getInputStream();
            try {
                //return _mapper.readValue(in, Response.class);
                byte[] bytes = Arrays.read(in);
                return _mapper.readValue(bytes, Response.class).setResponseBody(bytes);
            } finally {
                in.close();
            }
		} catch (Exception x) {
			//x.printStackTrace();
            throw new RuntimeException("RemoteServiceCallFailure", x);
		}
	}
}
