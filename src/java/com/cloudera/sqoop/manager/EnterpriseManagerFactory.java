// (c) Copyright 2010 Cloudera, Inc. All Rights Reserved.

package com.cloudera.sqoop.manager;

import com.cloudera.sqoop.SqoopOptions;
import com.cloudera.sqoop.netezza.DirectNetezzaManager;
import com.cloudera.sqoop.netezza.NetezzaManager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Contains instantiation code for all ConnManager implementations
 * shipped and enabled in the third-party connector library.
 */
public class EnterpriseManagerFactory extends ManagerFactory {

  public static final Log LOG = LogFactory.getLog(
      EnterpriseManagerFactory.class.getName());

  @Override
  public ConnManager accept(SqoopOptions options) {

    String connectStr = options.getConnectString();

    // java.net.URL follows RFC-2396 literally, which does not allow a ':'
    // character in the scheme component (section 3.1). JDBC connect strings,
    // however, commonly have a multi-scheme addressing system. e.g.,
    // jdbc:mysql://...; so we cannot parse the scheme component via URL
    // objects. Instead, attempt to pull out the scheme as best as we can.

    // First, see if this is of the form [scheme://hostname-and-etc..]
    int schemeStopIdx = connectStr.indexOf("//");
    if (-1 == schemeStopIdx) {
      // If no hostname start marker ("//"), then look for the right-most ':'
      // character.
      schemeStopIdx = connectStr.lastIndexOf(':');
      if (-1 == schemeStopIdx) {
        // Warn that this is nonstandard. But we should be as permissive
        // as possible here and let the ConnectionManagers themselves throw
        // out the connect string if it doesn't make sense to them.
        LOG.warn("Could not determine scheme component of connect string");

        // Use the whole string.
        schemeStopIdx = connectStr.length();
      }
    }

    String scheme = connectStr.substring(0, schemeStopIdx);

    if (null == scheme) {
      // We don't know if this is a mysql://, hsql://, etc.
      // Can't do anything with this.
      LOG.warn("Null scheme associated with connect string.");
      return null;
    }

    LOG.debug("Trying with scheme: " + scheme);

    if (scheme.equals("jdbc:netezza:")) {
      if (options.isDirect()) {
        return new DirectNetezzaManager(options);
      } else {
        return new NetezzaManager(options);
      }
    } else {
      return null;
    }
  }
}

