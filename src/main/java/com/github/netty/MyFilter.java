package com.github.netty;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.FileFilter;
import java.io.IOException;

/**
 * @author jerrylz
 * @date 2021/3/8
 */
public class MyFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        System.out.println("this is myFilter");
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {

    }
}
