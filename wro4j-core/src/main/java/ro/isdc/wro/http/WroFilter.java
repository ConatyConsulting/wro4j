/*
 * Copyright (c) 2008. All rights reserved.
 */
package ro.isdc.wro.http;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.management.JMException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ro.isdc.wro.WroRuntimeException;
import ro.isdc.wro.config.Context;
import ro.isdc.wro.config.WroConfigurationChangeListener;
import ro.isdc.wro.config.factory.PropertiesAndFilterConfigWroConfigurationFactory;
import ro.isdc.wro.config.jmx.WroConfiguration;
import ro.isdc.wro.manager.CacheChangeCallbackAware;
import ro.isdc.wro.manager.factory.BaseWroManagerFactory;
import ro.isdc.wro.manager.factory.WroManagerFactory;
import ro.isdc.wro.util.ObjectFactory;
import ro.isdc.wro.util.WroUtil;


/**
 * Main entry point. Perform the request processing by identifying the type of the requested resource. Depending on the
 * way it is configured.
 *
 * @author Alex Objelean
 * @created Created on Oct 31, 2008
 */
public class WroFilter
  implements Filter {
  private static final Logger LOG = LoggerFactory.getLogger(WroFilter.class);
  /**
   * The prefix to use for default mbean name.
   */
  private static final String MBEAN_PREFIX = "wro4j-";
  /**
   * Default value used by Cache-control header.
   */
  private static final String DEFAULT_CACHE_CONTROL_VALUE = "public, max-age=315360000";
  /**
   * wro API mapping path. If request uri contains this, exposed API method will be invoked.
   */
  public static final String PATH_API = "wroAPI";
  /**
   * API - reload cache method call
   */
  public static final String API_RELOAD_CACHE = PATH_API + "/reloadCache";
  /**
   * API - reload model method call
   */
  public static final String API_RELOAD_MODEL = PATH_API + "/reloadModel";

  /**
   * Filter config.
   */
  private FilterConfig filterConfig;
  /**
   * Wro configuration.
   */
  private WroConfiguration wroConfiguration;
  /**
   * WroManagerFactory. The brain of the optimizer.
   */
  private WroManagerFactory wroManagerFactory;

  /**
   * Map containing header values used to control caching. The keys from this values are trimmed and lower-cased when
   * put, in order to avoid duplicate keys. This is done, because according to RFC 2616 Message Headers field names are
   * case-insensitive.
   */
  @SuppressWarnings("serial")
  private final Map<String, String> headersMap = new LinkedHashMap<String, String>() {
    @Override
    public String put(final String key, final String value) {
      return super.put(key.trim().toLowerCase(), value);
    }


    @Override
    public String get(final Object key) {
      return super.get(((String)key).toLowerCase());
    }
  };

  /**
   * @return implementation of {@link WroConfigurationFactory} used to create a {@link WroConfiguration} object.
   */
  protected ObjectFactory<WroConfiguration> newWroConfigurationFactory() {
    return new PropertiesAndFilterConfigWroConfigurationFactory(filterConfig);
  }

  /**
   * {@inheritDoc}
   */
  public final void init(final FilterConfig config)
    throws ServletException {
    this.filterConfig = config;
    wroConfiguration = newWroConfigurationFactory().create();
    initWroManagerFactory();
    initHeaderValues();
    registerChangeListeners();
    initJMX();
    doInit(config);
  }


  /**
   * Initialize {@link WroManagerFactory}.
   */
  private void initWroManagerFactory() {
    this.wroManagerFactory = getWroManagerFactory();
    if (wroManagerFactory instanceof CacheChangeCallbackAware) {
      // register cache change callback -> when cache is changed, update headers values.
      ((CacheChangeCallbackAware)wroManagerFactory).registerCacheChangeListener(new PropertyChangeListener() {
        public void propertyChange(final PropertyChangeEvent evt) {
          // update header values
          initHeaderValues();
        }
      });
    }
  }

  /**
   * Expose MBean to tell JMX infrastructure about our MBean.
   */
  private void initJMX() {
    try {
      if (wroConfiguration.isJmxEnabled()) {
        final MBeanServer mbeanServer = getMBeanServer();
        final ObjectName name = new ObjectName(newMBeanName(), "type", WroConfiguration.class.getSimpleName());
        if (!mbeanServer.isRegistered(name)) {
          mbeanServer.registerMBean(wroConfiguration, name);
        }
      }
      LOG.info("wro4j configuration: " + wroConfiguration);
    } catch (final JMException e) {
      LOG.error("Exception occured while registering MBean", e);
    }
  }

  /**
   * @return the name of MBean to be used by JMX to configure wro4j.
   */
  protected String newMBeanName() {
    String mbeanName = wroConfiguration.getMbeanName();
    if (StringUtils.isEmpty(mbeanName)) {
      final String contextPath = getContextPath();
      mbeanName = StringUtils.isEmpty(contextPath) ? "ROOT" : contextPath;
      mbeanName = MBEAN_PREFIX + contextPath;
    }
    return mbeanName;
  }


  /**
   * @return Context path of the application.
   */
  private String getContextPath() {
    String contextPath = null;
    try {
      contextPath = (String)ServletContext.class.getMethod("getContextPath", new Class<?>[] {}).invoke(
        filterConfig.getServletContext(), new Object[] {});
    } catch (final Exception e) {
      contextPath = "DEFAULT";
      LOG.warn("Couldn't identify contextPath because you are using older version of servlet-api (<2.5). Using "
        + contextPath + " contextPath.");
    }
    return contextPath.replaceFirst("/", "");
  }


  /**
   * Override this method if you want to provide a different MBeanServer.
   *
   * @return {@link MBeanServer} to use for JMX.
   */
  protected MBeanServer getMBeanServer() {
    return ManagementFactory.getPlatformMBeanServer();
  }


  /**
   * Register property change listeners.
   */
  private void registerChangeListeners() {
    wroConfiguration.registerCacheUpdatePeriodChangeListener(new PropertyChangeListener() {
      public void propertyChange(final PropertyChangeEvent event) {
        // reset cache headers when any property is changed in order to avoid browser caching
        initHeaderValues();
        if (wroManagerFactory instanceof WroConfigurationChangeListener) {
          final long value = Long.valueOf(String.valueOf(event.getNewValue())).longValue();
          ((WroConfigurationChangeListener)wroManagerFactory).onCachePeriodChanged(value);
        }
      }
    });
    wroConfiguration.registerModelUpdatePeriodChangeListener(new PropertyChangeListener() {
      public void propertyChange(final PropertyChangeEvent event) {
        initHeaderValues();
        if (wroManagerFactory instanceof WroConfigurationChangeListener) {
          final long value = Long.valueOf(String.valueOf(event.getNewValue())).longValue();
          ((WroConfigurationChangeListener)wroManagerFactory).onModelPeriodChanged(value);
        }
      }
    });
    LOG.debug("Cache & Model change listeners were registered");
  }

  /**
   * Initialize header values.
   */
  private void initHeaderValues() {
    // put defaults
    if (!wroConfiguration.isDebug()) {
      final Long timestamp = new Date().getTime();
      final Calendar cal = Calendar.getInstance();
      cal.roll(Calendar.YEAR, 1);
      headersMap.put(HttpHeader.CACHE_CONTROL.toString(), DEFAULT_CACHE_CONTROL_VALUE);
      headersMap.put(HttpHeader.LAST_MODIFIED.toString(), WroUtil.toDateAsString(timestamp));
      headersMap.put(HttpHeader.EXPIRES.toString(), WroUtil.toDateAsString(cal.getTimeInMillis()));
    }
    final String headerParam = wroConfiguration.getHeader();
    if (!StringUtils.isEmpty(headerParam)) {
      try {
        if (headerParam.contains("|")) {
          final String[] headers = headerParam.split("[|]");
          for (final String header : headers) {
            parseHeader(header);
          }
        } else {
          parseHeader(headerParam);
        }
      } catch (final Exception e) {
        throw new WroRuntimeException("Invalid header init-param value: " + headerParam
          + ". A correct value should have the following format: "
          + "<HEADER_NAME1>: <VALUE1> | <HEADER_NAME2>: <VALUE2>. " + "Ex: <look like this: "
          + "Expires: Thu, 15 Apr 2010 20:00:00 GMT | cache-control: public", e);
      }
    }
    LOG.debug("Header Values: {}", headersMap);
  }


  /**
   * Parse header value & puts the found values in headersMap field.
   *
   * @param header value to parse.
   */
  private void parseHeader(final String header) {
    LOG.debug("parseHeader: {}", header);
    final String headerName = header.substring(0, header.indexOf(":"));
    if (!headersMap.containsKey(headerName)) {
      headersMap.put(headerName, header.substring(header.indexOf(":") + 1));
    }
  }

  /**
   * Custom filter initialization - can be used for extended classes.
   *
   * @see Filter#init(FilterConfig).
   */
  protected void doInit(final FilterConfig config)
    throws ServletException {}


  /**
   * {@inheritDoc}
   */
  public final void doFilter(final ServletRequest req, final ServletResponse res, final FilterChain chain)
    throws IOException, ServletException {
    final HttpServletRequest request = (HttpServletRequest)req;
    final HttpServletResponse response = (HttpServletResponse)res;
    try {
      // add request, response & servletContext to thread local
      Context.set(Context.webContext(request, response, filterConfig), wroConfiguration);

      // TODO move API related checks into separate class and determine filter mapping for better mapping
      if (shouldReloadCache()) {
        Context.get().getConfig().reloadCache();
        WroUtil.addNoCacheHeaders(response);
        //set explicitly status OK for unit testing
        response.setStatus(HttpServletResponse.SC_OK);
      } else if (shouldReloadModel()) {
        Context.get().getConfig().reloadModel();
        WroUtil.addNoCacheHeaders(response);
        //set explicitly status OK for unit testing
        response.setStatus(HttpServletResponse.SC_OK);
      } else {
        processRequest(request, response);
        onRequestProcessed();
      }
    } catch (final RuntimeException e) {
      onRuntimeException(e, response, chain);
    } finally {
      Context.unset();
    }
  }

  /**
   * Useful for unit tests to check the post processing.
   */
  protected void onRequestProcessed() {
  }

  /**
   * @return true if reload model must be triggered.
   */
  private boolean shouldReloadModel() {
    return Context.get().getConfig().isDebug() && matchesUrl(API_RELOAD_MODEL);
  }

  /**
   * @return true if reload cache must be triggered.
   */
  private boolean shouldReloadCache() {
    return Context.get().getConfig().isDebug() && matchesUrl(API_RELOAD_CACHE);
  }

  /**
   * Check if the request path matches the provided api path.
   */
  private boolean matchesUrl(final String apiPath) {
    final HttpServletRequest request = Context.get().getRequest();
    final Pattern pattern = Pattern.compile(".*" + apiPath + "[/]?", Pattern.CASE_INSENSITIVE);
    final Matcher m = pattern.matcher(request.getRequestURI());
    return m.matches();
  }

  /**
   * Perform actual processing.
   */
  private void processRequest(final HttpServletRequest request, final HttpServletResponse response)
    throws ServletException, IOException {
    setResponseHeaders(response);
    // process the uri using manager
    wroManagerFactory.create().process();
  }


  /**
   * Invoked when a {@link RuntimeException} is thrown. Allows custom exception handling. The default implementation
   * redirects to 404 for a specific {@link WroRuntimeException} exception when in DEPLOYMENT mode.
   *
   * @param e {@link RuntimeException} thrown during request processing.
   */
  protected void onRuntimeException(final RuntimeException e, final HttpServletResponse response,
    final FilterChain chain) {
    LOG.debug("RuntimeException occured", e);
    try {
      LOG.debug("Cannot process. Proceeding with chain execution.");
      chain.doFilter(Context.get().getRequest(), response);
    } catch (final Exception ex) {
      // should never happen
      LOG.error("Error while chaining the request: " + HttpServletResponse.SC_NOT_FOUND);
    }
  }


  /**
   * Method called for each request and responsible for setting response headers, used mostly for cache control.
   * Override this method if you want to change the way headers are set.<br>
   *
   * @param response {@link HttpServletResponse} object.
   */
  protected void setResponseHeaders(final HttpServletResponse response) {
    // Force resource caching as best as possible
    for (final Map.Entry<String, String> entry : headersMap.entrySet()) {
      response.setHeader(entry.getKey(), entry.getValue());
    }
    //prevent caching when in development mode
    if (wroConfiguration.isDebug()) {
      WroUtil.addNoCacheHeaders(response);
    }
  }


  /**
   * Factory method for {@link WroManagerFactory}. Override this method, in order to change the way filter use factory.
   *
   * @return {@link WroManagerFactory} object.
   */
  protected WroManagerFactory getWroManagerFactory() {
    if (StringUtils.isEmpty(wroConfiguration.getWroManagerClassName())) {
      // If no context param was specified we return the default factory
      return newWroManagerFactory();
    } else {
      // Try to find the specified factory class
      Class<?> factoryClass = null;
      try {
        factoryClass = Thread.currentThread().getContextClassLoader().loadClass(
          wroConfiguration.getWroManagerClassName());
        // Instantiate the factory
        return (WroManagerFactory)factoryClass.newInstance();
      } catch (final Exception e) {
        throw new WroRuntimeException("Exception while loading WroManagerFactory class", e);
      }
    }
  }

  /**
   * @return default implementation of {@link WroManagerFactory} when none is provided explicitly through
   *         wroConfiguration option.
   */
  protected WroManagerFactory newWroManagerFactory() {
    return new BaseWroManagerFactory();
  }


  /**
   * @return the {@link WroConfiguration} associated with this filter instance.
   */
  public final WroConfiguration getWroConfiguration() {
    return this.wroConfiguration;
  }


  /**
   * {@inheritDoc}
   */
  public void destroy() {
    wroManagerFactory.destroy();
    wroConfiguration.destroy();
    Context.destroy();
  }
}
