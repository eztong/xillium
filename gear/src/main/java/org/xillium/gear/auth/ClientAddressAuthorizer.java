package org.xillium.gear.auth;

import java.util.*;
import java.util.regex.*;
import java.util.logging.*;
import java.net.InetAddress;
import org.xillium.base.beans.Strings;
import org.xillium.data.*;
import org.xillium.core.*;
import org.xillium.core.management.ManagedComponent;


/**
 * An Authorizer that authorizes by matching client addresses against authorized patterns.
 * <p/>
 * Accepted IP address patterns include:
 * <ul>
 * <li> "192.168.1.*",
 * <li> "172.21.*.*",
 * <li> "234.66.*.10",
 * <li> "0:0:0:0:0:0:0:1"
 * </ul>
 * Zero-compression in IPv6 addresses is not permitted in address patterns. This restriction does not apply to
 * client addresses to be authorized.
 * <p/>
 * You can also use the <code>allowingPrivate</code> property to authorize all private IP addresses, as shown
 * in this Spring bean definition.
 * <xmp>
 *  <bean id="ipguard" class="org.xillium.gear.auth.ClientAddressAuthorizer">
 *      <property name="allowingPrivate" value="true"/>
 *      ...
 *  </bean>
 * </xmp>
 */
public class ClientAddressAuthorizer extends ManagedComponent implements Authorizer {
	private static final Logger _logger = Logger.getLogger(ClientAddressAuthorizer.class.getName());
    private static final Pattern IPv4_ADDRESS_PATTERN = Pattern.compile(
        "([0-9*]{1,3})\\.([0-9*]{1,3})\\.([0-9*]{1,3})\\.([0-9*]{1,3})"
    );
    private static final Pattern IPv6_ADDRESS_PATTERN = Pattern.compile(
        "([0-9a-f*]{1,4}):([0-9a-f*]{1,4}):([0-9a-f*]{1,4}):([0-9a-f*]{1,4}):([0-9a-f*]{1,4}):([0-9a-f*]{1,4}):([0-9a-f*]{1,4}):([0-9a-f*]{1,4})",
        Pattern.CASE_INSENSITIVE
    );

    private final List<short[]> _ipv4patterns = new ArrayList<short[]>();
    private final List<short[]> _ipv6patterns = new ArrayList<short[]>();
    private boolean _allowingPrivate;


    /**
     * Constructs a ClientAddressAuthorizer.
     */
    public ClientAddressAuthorizer() {
    }

    /**
     * Constructs a ClientAddressAuthorizer.
     *
     * @param patterns - an array of address patterns
     */
	public ClientAddressAuthorizer(String[] patterns) {
		setAuthorizedPatternArray(patterns);
	}

    /**
     * Adds authorized address patterns as a comma-separated list.
     */
    public void setAuthorizedPatterns(String patterns) {
        setAuthorizedPatternArray(patterns.split(" *, *"));
    }

    /**
     * Sets whether to authorize all private addresses.
     */
    public void setAllowingPrivate(boolean allowing) {
        _allowingPrivate = allowing;
    }

    /**
     * Adds authorized address patterns as a String array.
     */
    public void setAuthorizedPatternArray(String[] patterns) {
        Matcher matcher;

        patterns: for (String pattern: patterns) {
            if ((matcher = IPv4_ADDRESS_PATTERN.matcher(pattern)).matches()) {
                short[] address = new short[4];
                for (int i = 0; i < address.length; ++i) {
                    String p = matcher.group(i+1);
                    try {
                        address[i] = Short.parseShort(p);
                        if (address[i] > 255) {
                            _logger.warning("Invalid pattern ignored: " + pattern);
                            continue patterns;
                        }
                    } catch (Exception x) {
                        if ("*".equals(p)) {
                            address[i] = 256;
                        } else {
                            _logger.warning("Invalid pattern ignored: " + pattern);
                            continue patterns;
                        }
                    }
                }
                _logger.fine("normalized ipv4 address pattern = " + Strings.join(address, '.'));
                _ipv4patterns.add(address);
            } else if ((matcher = IPv6_ADDRESS_PATTERN.matcher(pattern)).matches()) {
                short[] address = new short[16];
                for (int i = 0; i < address.length; i += 2) {
                    String p = matcher.group(i/2+1);
                    try {
                        int v = Integer.parseInt(p, 16);
                        address[i] = (short)(v >> 8);
                        address[i+1] = (short)(v & 0x00ff);
                    } catch (Exception x) {
                        if ("*".equals(p)) {
                            address[i] = 256;
                            address[i+1] = 256;
                        } else {
                            _logger.warning("Invalid pattern ignored: " + pattern);
                            continue patterns;
                        }
                    }
                }
                _logger.fine("normalized ipv6 address pattern = " + Strings.join(address, '.'));
                _ipv6patterns.add(address);
            } else {
                _logger.warning("Invalid pattern ignored: " + pattern);
            }
        }
    }

    @Override
    public void authorize(Service service, String deployment, DataBinder parameters, Persistence persist) throws AuthorizationException {
        String address = parameters.get(Service.REQUEST_CLIENT_ADDR);
        _logger.fine("checking address " + address);
        try {
            InetAddress inet = InetAddress.getByName(address); 
            if (_allowingPrivate && (inet.isLoopbackAddress() || inet.isLinkLocalAddress() || inet.isSiteLocalAddress())) {
                return;
            }

            byte[] bytes = inet.getAddress();

  patterns: for (short[] pattern: bytes.length == 4 ? _ipv4patterns : _ipv6patterns) {
                for (int i = 0; i < pattern.length; ++i) {
                    if (pattern[i] < 256 && pattern[i] != (bytes[i] & 0xff)) {
                        continue patterns;
                    }
                }
                _logger.fine("pattern match " + Strings.join(pattern, '.'));
                return;
            }
        } catch (java.net.UnknownHostException x) {
            throw new AuthorizationException(x.getMessage(), x);
        }
        throw new AuthorizationException(address);
    }
}
