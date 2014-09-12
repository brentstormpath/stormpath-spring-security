/*
 * Copyright 2014 Stormpath, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stormpath.spring.security.servlet.http;

import com.stormpath.sdk.account.Account;
import com.stormpath.sdk.idsite.IdSiteCallbackHandler;
import com.stormpath.sdk.idsite.IdSiteResultListener;
import com.stormpath.spring.security.authc.IdSiteAccountIDField;
import com.stormpath.spring.security.authc.IdSiteAuthenticationToken;
import com.stormpath.spring.security.provider.StormpathAuthenticationProvider;
import com.stormpath.spring.security.servlet.conf.Configuration;
import com.stormpath.spring.security.servlet.conf.UrlFor;
import com.stormpath.spring.security.servlet.service.IdSiteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.Assert;
import org.springframework.web.context.support.WebApplicationContextUtils;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This servlet is in charge of handling all the communication with Stormpath's IDSite.
 * <p/>
 * In order for it to properly work in your Web Application, you need to enable the auto-scanning feature for the following
 * package: com.stormpath.spring.security.servlet.service. For example, in your application's servlet context definition:
 * <p/>
 * <pre>
 *      <context:component-scan base-package="com.stormpath.spring.security.servlet.service" />
 * </pre>
 * <p/>
 * This way, this servlet will be able to get the internal bean instances it requires.
 *
 * @since 0.4.0
 */
@WebListener
public class IdSiteServlet extends HttpServlet implements ServletContextListener {

    private static final Logger logger = LoggerFactory.getLogger(IdSiteServlet.class);

    // This servlet will only handle requests for these URIs
    private static final String IDSITE_LOGIN_ACTION = UrlFor.get("idsite_login.action");
    private static final String IDSITE_LOGOUT_ACTION = UrlFor.get("idsite_logout.action");

    private static final String IDSITE_LOGIN_CALLBACK_ACTION = UrlFor.get("idsite_login_callback.action");
    private static final String IDSITE_LOGIN_REDIRECT_URL = Configuration.getBaseUrl() + IDSITE_LOGIN_CALLBACK_ACTION;

    private static final String IDSITE_LOGOUT_CALLBACK_ACTION = UrlFor.get("idsite_logout_callback.action");
    private static final String IDSITE_LOGOUT_REDIRECT_URL = Configuration.getBaseUrl() + IDSITE_LOGOUT_CALLBACK_ACTION;

    protected static IdSiteService idSiteService;
    protected static StormpathAuthenticationProvider authenticationProvider;
    private static IdSiteResultListener idSiteResultListener;

    private IdSiteAccountIDField idSitePrincipalAccountIdField = IdSiteAccountIDField.EMAIL;

    /**
     * All ID Site-related requests are handled here relying on the {@link com.stormpath.spring.security.servlet.service.IdSiteService} and {@link StormpathAuthenticationProvider} to
     * do all the work.
     *
     * @param request  an {@link javax.servlet.http.HttpServletRequest} object that contains the request the client has made of the servlet.
     * @param response an {@link javax.servlet.http.HttpServletResponse} object that contains the response the servlet sends to the client.
     * @throws java.io.IOException            if an input or output error is detected when the servlet handles the POST request
     * @throws javax.servlet.ServletException if the request for the POST could not be handled
     */
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String uri = request.getRequestURI();

        if (uri.startsWith(IDSITE_LOGIN_ACTION)) {
            //Displays the ID Site login screen
            processLogin(response);
        } else if (uri.startsWith(IDSITE_LOGIN_CALLBACK_ACTION)) {
            //Instructs Spring Security about the actual account login, updating the security context and setting all account's permissions.
            processLoginCallback(request, response);
        } else if (uri.startsWith(IDSITE_LOGOUT_ACTION)) {
            //Sends the logout to ID Site
            processLogout(response);
        } else if (uri.startsWith(IDSITE_LOGOUT_CALLBACK_ACTION)) {
            //Instructs Spring Security about the actual account logout, clearing the security context.
            processLogoutCallback(request, response);
        }
    }

    protected void processLogin(HttpServletResponse response) throws ServletException, IOException {
        //Perform login via ID Site
        logger.debug("Redirecting to the following IDSite Redirect URL: " + IDSITE_LOGIN_REDIRECT_URL);
        addIDSiteHeader(response);
        String callbackUri = idSiteService.getLoginRedirectUri(IDSITE_LOGIN_REDIRECT_URL);
        response.sendRedirect(callbackUri);
    }

    protected void processLoginCallback(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        IdSiteCallbackHandler callbackHandler = idSiteService.getCallbackHandler(request, this.idSiteResultListener);
        //At this point, the IdSiteResultListener will be notified about the login
        Account account = callbackHandler.getAccountResult().getAccount();
        Authentication authentication = authenticationProvider.authenticate(new IdSiteAuthenticationToken(getIdSitePrincipalValue(account), account));
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(authentication);
        response.sendRedirect(Configuration.getLoginRedirectUri());
    }

    protected void processLogout(HttpServletResponse response) throws ServletException, IOException {
        logger.debug("Redirecting to the following IDSite Redirect URL: " + IDSITE_LOGOUT_REDIRECT_URL);
        addIDSiteHeader(response);
        String callbackUri = idSiteService.getLogoutRedirectUri(IDSITE_LOGOUT_REDIRECT_URL);
        response.sendRedirect(callbackUri);
    }

    protected void processLogoutCallback(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        SecurityContextHolder.clearContext();
        IdSiteCallbackHandler callbackHandler = idSiteService.getCallbackHandler(request, idSiteResultListener);
        //We need to invoke this operation in order for the IdSiteResultListener to be notified about the logout
        callbackHandler.getAccountResult();
        response.sendRedirect(Configuration.getLoginRedirectUri());
    }

    private void addIDSiteHeader(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, post-check=0, pre-check=0");
        response.setHeader("Pragma", "no-cache");
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        BeanFactory beanFactory = getBeanFactory(sce.getServletContext());
        idSiteService = (IdSiteService) beanFactory.getBean("idSiteService");
        authenticationProvider = (StormpathAuthenticationProvider) beanFactory.getBean("authenticationProvider");
        if (beanFactory.containsBean("idSiteResultListener")) {
            idSiteResultListener = (IdSiteResultListener) beanFactory.getBean("idSiteResultListener");
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
    }

    private Map<String, String[]> getHttpRequestHeaders(final HttpServletRequest request) {
        Map<String, String[]> headers = new LinkedHashMap<String, String[]>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, new String[] {request.getHeader(headerName)});
        }
        return headers;
    }

    protected BeanFactory getBeanFactory(ServletContext context) {
        return WebApplicationContextUtils
                .getRequiredWebApplicationContext(context)
                .getAutowireCapableBeanFactory();
    }

    /**
     * Configures the field that will be set as the principal when creating the {@link org.springframework.security.core.Authentication authentication token}
     * after a successful ID Site login.
     * <p/>
     * When users login via ID Site, we do not have access to the actual login information. Thus, we do not know whether the
     * user logged in with his username or his email. Via this field, the developer can configure whether the principal information
     * will be either the {@link com.stormpath.spring.security.authc.IdSiteAccountIDField#USERNAME account username} or the {@link com.stormpath.spring.security.authc.IdSiteAccountIDField#EMAIL account email}.
     * <p/>
     * By default, the account `email` is used.
     *
     * @param idField either `username` or `email` to express the desired principal to set when constructing the {@Link Authentication authentication token}
     *                after a successful ID Site login.
     *
     * @see com.stormpath.spring.security.authc.IdSiteAccountIDField
     * @since 0.4.0
     */
    public void setIdSitePrincipalAccountIdField(String idField) {
        Assert.notNull(idField);
        this.idSitePrincipalAccountIdField = IdSiteAccountIDField.fromName(idField);
    }

    /**
     * Returns the account field that will be used as the principal for the {@link org.springframework.security.core.Authentication authentication token}
     * after a successful ID Site login.
     *
     * @return the account field that will be used as the principal for the {@link org.springframework.security.core.Authentication authentication token}
     * after a successful ID Site login.
     *
     * @since 0.4.0
     */
    public String getIdSitePrincipalAccountIdField() {
        return this.idSitePrincipalAccountIdField.toString();
    }

    //@since 0.4.0
    private String getIdSitePrincipalValue(Account account) {
        switch (this.idSitePrincipalAccountIdField) {
            case EMAIL:
                return account.getEmail();
            case USERNAME:
                return account.getUsername();
            default:
                throw new UnsupportedOperationException("unrecognized idSitePrincipalAccountIdField value: " + this.idSitePrincipalAccountIdField);
        }
    }

}