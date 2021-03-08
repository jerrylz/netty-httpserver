package com.github.netty;

import com.github.netty.servlet.ServletContext;
import com.github.netty.springboot.NettyEmbeddedServletContainer;
import com.github.netty.springboot.NettyEmbeddedServletContainerFactory;
import org.springframework.boot.web.servlet.ServletContextInitializer;

import javax.net.ssl.SSLException;

/**
 * @author jerrylz
 * @date 2021/3/7
 */
public class StartupServer {
    public static void main(String[] args) throws SSLException {
        NettyEmbeddedServletContainerFactory factory = new NettyEmbeddedServletContainerFactory();
        ServletContext servletContext = factory.newServletContext();
        servletContext.addServlet("test", TestServlet.class).addMapping("/test");
        servletContext.addFilter("/myFilter", MyFilter.class).addMappingForUrlPatterns(null,true, "/*");
        NettyEmbeddedServletContainer container = factory.newNettyEmbeddedServletContainer(servletContext);

        container.start();
//        if (isRegisterDefaultServlet()) {
//            registerDefaultServlet(servletContext);
//        }

//        for (
//                ServletContextInitializer initializer : initializers) {
//            initializer.onStartup(servletContext);
//        }
    }
}
