package com.abchina.servlet;

import com.abchina.util.MimeTypeUtil;
import com.abchina.util.NamespaceUtil;
import com.abchina.core.constants.HttpConstants;
import com.abchina.util.TypeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import javax.servlet.descriptor.JspConfigDescriptor;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;

/**
 *
 *  servlet容器上下文
 */
public class ServletContext implements javax.servlet.ServletContext {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private Map<String,ServletHttpSession> httpSessionMap;
    private Map<String,Object> attributeMap;
    private Map<String,String> initParamMap;

    private Map<String, com.abchina.servlet.ServletRegistration> servletRegistrationMap;
    private Map<String,ServletFilterRegistration> filterRegistrationMap;

    private ExecutorService asyncExecutorService;

    private List<EventListener> eventListenerList;
    private Set<SessionTrackingMode> sessionTrackingModeSet;

    private ServletSessionCookieConfig sessionCookieConfig;
    private RequestUrlPatternMapper servletUrlPatternMapper;
    private String rootDirStr;
    private Charset defaultCharset;
    private InetSocketAddress serverSocketAddress;
    private final String serverInfo;
    private final ClassLoader classLoader;
    private String contextPath;
    private volatile boolean initialized; //记录是否初始化完毕

    public ServletContext(InetSocketAddress socketAddress,
                          ClassLoader classLoader,
                          String contextPath, String serverInfo,
                          ServletSessionCookieConfig sessionCookieConfig) {
        this.initialized = false;
        this.sessionCookieConfig = sessionCookieConfig;
        this.serverInfo = serverInfo == null? "netty-server/1.0":serverInfo;

        this.contextPath = contextPath == null? "" : contextPath;
        this.defaultCharset = null;
        this.eventListenerList = null;
        this.asyncExecutorService = null;
        this.sessionTrackingModeSet = null;
        this.serverSocketAddress = socketAddress;
        this.classLoader = classLoader;

        this.httpSessionMap = new ConcurrentHashMap<>();
        this.attributeMap = new ConcurrentHashMap<>();
        this.initParamMap = new ConcurrentHashMap<>();
        this.servletRegistrationMap = new ConcurrentHashMap<>();
        this.filterRegistrationMap = new ConcurrentHashMap<>();
        this.servletUrlPatternMapper = new RequestUrlPatternMapper(contextPath);

        //一分钟检查一次过期session
        new SessionInvalidThread(NamespaceUtil.newIdName(this,"SessionInvalidThread"),60 * 1000).start();
    }

    public void addServletMapping(String urlPattern, String name, Servlet servlet) throws ServletException {
        servletUrlPatternMapper.addServlet(urlPattern, servlet, name);
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean initialized) {
        this.initialized = initialized;
    }

    public ExecutorService getAsyncExecutorService() {
        if(asyncExecutorService == null) {
            asyncExecutorService = Executors.newFixedThreadPool(8);
        }
        return asyncExecutorService;
    }

    public long getAsyncTimeout(){
        String value = getInitParameter("asyncTimeout");
        if(value == null){
            return 10000;
        }
        try {
            return Long.parseLong(value);
        }catch (NumberFormatException e){
            return 10000;
        }
    }

    public InetSocketAddress getServerSocketAddress() {
        return serverSocketAddress;
    }

    public Map<String, ServletHttpSession> getHttpSessionMap() {
        return httpSessionMap;
    }

    public Charset getDefaultCharset() {
        if(defaultCharset == null){
            defaultCharset = HttpConstants.DEFAULT_CHARSET;
        }
        return defaultCharset;
    }

    @Override
    public String getContextPath() {
        return contextPath;
    }

    @Override
    public ServletContext getContext(String uripath) {
        return this;
    }

    @Override
    public int getMajorVersion() {
        return 3;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    @Override
    public int getEffectiveMajorVersion() {
        return 3;
    }

    @Override
    public int getEffectiveMinorVersion() {
        return 0;
    }

    @Override
    public String getMimeType(String file) {
        return MimeTypeUtil.getMimeTypeByFileName(file);
    }

    @Override
    public Set<String> getResourcePaths(String path) {
        Set<String> thePaths = new HashSet<>();
        if (!path.endsWith("/")) {
            path += "/";
        }
        String basePath = getRealPath(path);
        if (basePath == null) {
            return thePaths;
        }
        File theBaseDir = new File(basePath);
        if (!theBaseDir.exists() || !theBaseDir.isDirectory()) {
            return thePaths;
        }
        String theFiles[] = theBaseDir.list();
        if (theFiles == null) {
            return thePaths;
        }
        for (String filename : theFiles) {
            File testFile = new File(basePath + File.separator + filename);
            if (testFile.isFile()) {
                thePaths.add(path + filename);
            } else if (testFile.isDirectory()) {
                thePaths.add(path + filename + "/");
            }
        }
        return thePaths;
    }

    @Override
    public URL getResource(String path) throws MalformedURLException {
        if (!path.startsWith("/")) {
            throw new MalformedURLException("Path '" + path + "' does not start with '/'");
        }
        URL url = new URL(getClassLoader().getResource(""), path.substring(1));
        try {
            url.openStream();
        } catch (Throwable t) {
            logger.warn("Throwing exception when getting InputStream of " + path + " in /");
            url = null;
        }
        return url;
    }

    @Override
    public InputStream getResourceAsStream(String path) {
        return getClass().getResourceAsStream(path);
    }

    @Override
    public ServletRequestDispatcher getRequestDispatcher(String path) {
        String servletName = servletUrlPatternMapper.getServletNameByRequestURI(path);
        return getNamedDispatcher(servletName);
    }

    @Override
    public ServletRequestDispatcher getNamedDispatcher(String name) {
        Servlet servlet;
        try {
            servlet = null == name ? null : getServlet(name);
            if (servlet == null) {
                return null;
            }

            //TODO 过滤器的urlPatter解析
            List<Filter> allNeedFilters = new ArrayList<>();
            for (ServletFilterRegistration registration : filterRegistrationMap.values()) {
                allNeedFilters.add(registration.getFilter());
            }
            FilterChain filterChain = new ServletFilterChain(servlet, allNeedFilters);
            return new ServletRequestDispatcher(this, filterChain);
        } catch (ServletException e) {
            logger.error("Throwing exception when getting Filter from ServletFilterRegistration of name " + name, e);
            return null;
        }
    }

    @Override
    public Servlet getServlet(String name) throws ServletException {
        com.abchina.servlet.ServletRegistration registration = servletRegistrationMap.get(name);
        if(registration == null){
            return null;
        }
        return registration.getServlet();
    }

    @Override
    public Enumeration<Servlet> getServlets() {
        List<Servlet> list = new ArrayList<>();
        for(com.abchina.servlet.ServletRegistration registration : servletRegistrationMap.values()){
            list.add(registration.getServlet());
        }
        return Collections.enumeration(list);
    }

    @Override
    public Enumeration<String> getServletNames() {
        List<String> list = new ArrayList<>();
        for(com.abchina.servlet.ServletRegistration registration : servletRegistrationMap.values()){
            list.add(registration.getName());
        }
        return Collections.enumeration(list);
    }

    @Override
    public void log(String msg) {
        logger.debug(msg);
    }

    @Override
    public void log(Exception exception, String msg) {
        logger.debug(msg,exception);
    }

    @Override
    public void log(String message, Throwable throwable) {
        logger.debug(message,throwable);
    }

    @Override
    public String getRealPath(String path) {
        return path;
    }

    @Override
    public String getServerInfo() {
        return serverInfo;
    }

    @Override
    public String getInitParameter(String name) {
        return initParamMap.get(name);
    }

    public <T>T getInitParameter(String name,T def) {
        String value = getInitParameter(name);
        if(value == null){
            return def;
        }
        Class<?> clazz = def.getClass();
        Object valCast = TypeUtil.cast((Object) value,clazz);
        if(valCast != null && valCast.getClass().isAssignableFrom(clazz)){
            return (T) valCast;
        }
        return def;
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParamMap.keySet());
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        return initParamMap.putIfAbsent(name,value) == null;
    }

    @Override
    public Object getAttribute(String name) {
        return attributeMap.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributeMap.keySet());
    }

    @Override
    public void setAttribute(String name, Object object) {
        attributeMap.put(name,object);
    }

    @Override
    public void removeAttribute(String name) {
        attributeMap.remove(name);
    }

    @Override
    public String getServletContextName() {
        return getClass().getSimpleName();
    }

    @Override
    public com.abchina.servlet.ServletRegistration addServlet(String servletName, String className) {
        try {
            Class<? extends Servlet> clazz = (Class<? extends Servlet>)getClassLoader().loadClass(className);
            return addServlet(servletName, clazz);
        } catch (ClassNotFoundException e){
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public com.abchina.servlet.ServletRegistration addServlet(String servletName, Servlet servlet) {
        com.abchina.servlet.ServletRegistration servletRegistration = new com.abchina.servlet.ServletRegistration(servletName,servlet,this);
        servletRegistrationMap.put(servletName,servletRegistration);
        return servletRegistration;
    }

    @Override
    public com.abchina.servlet.ServletRegistration addServlet(String servletName, Class<? extends Servlet> servletClass) {
        Servlet servlet = null;
        try {
            servlet = servletClass.newInstance();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }

        return addServlet(servletName,servlet);
    }

    @Override
    public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        try {
            return clazz.newInstance();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public javax.servlet.ServletRegistration getServletRegistration(String servletName) {
        return servletRegistrationMap.get(servletName);
    }

    @Override
    public Map<String, com.abchina.servlet.ServletRegistration> getServletRegistrations() {
        return servletRegistrationMap;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        try {
            Class<? extends Filter> clazz = (Class<? extends Filter>)getClassLoader().loadClass(className);
            return addFilter(filterName, clazz);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        ServletFilterRegistration registration = new ServletFilterRegistration(filterName,filter);
        filterRegistrationMap.put(filterName,registration);
        return registration;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        try {
            return addFilter(filterName,filterClass.newInstance());
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        try {
            return clazz.newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filterRegistrationMap.get(filterName);
    }

    @Override
    public Map<String, ServletFilterRegistration> getFilterRegistrations() {
        return filterRegistrationMap;
    }

    @Override
    public ServletSessionCookieConfig getSessionCookieConfig() {
        return sessionCookieConfig;
    }

    @Override
    public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {
        sessionTrackingModeSet = sessionTrackingModes;
    }

    @Override
    public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {
        return sessionTrackingModeSet;
    }

    @Override
    public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {
        return sessionTrackingModeSet;
    }

    @Override
    public void addListener(String className) {
        try {
            addListener((Class<? extends EventListener>) Class.forName(className));
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    @Override
    public <T extends EventListener> void addListener(T t) {
        boolean match = false;
        if (t instanceof ServletContextAttributeListener ||
                t instanceof ServletRequestListener ||
                t instanceof ServletRequestAttributeListener ||
                t instanceof HttpSessionIdListener ||
                t instanceof HttpSessionAttributeListener) {
//            eventListenerList.add(t);
            match = true;
        }

        if (t instanceof HttpSessionListener
                || (t instanceof ServletContextListener)) {
            match = true;
        }

        if (match) {
            if(eventListenerList == null) {
                eventListenerList = new CopyOnWriteArrayList<>();
            }
            eventListenerList.add(t);
            return;
        }

        if (t instanceof ServletContextListener) {
            throw new IllegalArgumentException(
                    "applicationContext.addListener.iae.sclNotAllowed"+
                    t.getClass().getName());
        } else {
            throw new IllegalArgumentException("applicationContext.addListener.iae.wrongType"+
                    t.getClass().getName());
        }
    }

    @Override
    public void addListener(Class<? extends EventListener> listenerClass) {
        try {
            addListener(listenerClass.newInstance());
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {
        try {
            return clazz.newInstance();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public JspConfigDescriptor getJspConfigDescriptor() {
        return null;
    }

    @Override
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    @Override
    public void declareRoles(String... roleNames) {

    }

    @Override
    public String getVirtualServerName() {
        return serverSocketAddress.getHostString();
    }

    /**
     * 超时的Session无效化，定期执行
     */
    class SessionInvalidThread extends Thread {
        Logger logger = LoggerFactory.getLogger(getClass());

        private final long sessionLifeCheckInter;

        SessionInvalidThread(String name,long sessionLifeCheckInter) {
            this.sessionLifeCheckInter = sessionLifeCheckInter;
            setName(name);
        }

        @Override
        public void run() {
            logger.info("Session Manager CheckInvalidSessionThread has been started...");
            while(true){
                try {
                    Thread.sleep(sessionLifeCheckInter);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                for(ServletHttpSession session : httpSessionMap.values()){
                    if(!session.isValid()){
                        logger.info("Session(ID={}) is invalidated by Session Manager", session.getId());
                        session.invalidate();
                    }
                }
            }
        }
    }
}
