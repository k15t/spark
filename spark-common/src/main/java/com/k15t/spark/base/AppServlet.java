package com.k15t.spark.base;

import com.k15t.spark.base.util.NgTranslateMessageBundleProvider;
import org.apache.commons.lang3.StringUtils;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;


/**
 * <p>Serves resources.</p>
 * <p>As an example, this servlet is supposed to be listening to the URL pattern <code>https://example.com/servlet/**</code>.
 * Inside the classpath, there is a directory <code>/webapp</code> that contains a web application. The file
 * <code>/scripts/test.js</code> from this directory can be accessed using the following two URLs:
 * <code>https://example.com/servlet/scripts/test.js</code> and
 * <code>https://example.com/servlet/_/&lt;deploymentNumber&gt;/&lt;localeKey&gt;/scripts/test.js</code>.</p>
 * <p>The following terminology will be used in this code:</p>
 * <ul>
 * <li><code>resourcePath</code> is <code>&quot;/webapp/&quot;</code></li>
 * <li><code>cacheKey</code> would be <code>&quot;_/&lt;deploymentNumber&gt;/&lt;localeKey&gt;/&quot;</code> in the
 * second URL</li>
 * <li><code>localPath</code> is <code>&quot;scripts/test.js</code></li>
 * <li><code>localUrlPart</code> is <code>&quot;/scripts/test.js</code> in the first example,
 * <code>/_/&lt;deploymentNumber&gt;/&lt;localeKey&gt;/scripts/test.js</code> in the second.</li>
 * </ul>
 */

abstract public class AppServlet extends HttpServlet {

    protected final Set<String> VELOCITY_TYPES = Collections.unmodifiableSet(new HashSet<String>() {{
        add("text/html");
    }});

    private MessageBundleProvider messageBundleProvider;

    private String resourcePath;


    @Override
    public void init() throws ServletException {
        resourcePath = getServletConfig().getInitParameter(Keys.RESOURCE_PATH);
        if (resourcePath == null) {
            throw new ServletException(Keys.RESOURCE_PATH + " parameter is not defined");
        }

        if (!"/".equals(resourcePath.substring(resourcePath.length() - 1))) {
            resourcePath = resourcePath + "/";
        }

        this.messageBundleProvider = initMessageBundleProvider();
    }


    protected MessageBundleProvider initMessageBundleProvider() {
        String msgBundleResourcePath = getServletConfig().getInitParameter(Keys.NG_TRANS_MSG_BUNDLE);
        if (msgBundleResourcePath != null) {
            return new NgTranslateMessageBundleProvider(msgBundleResourcePath);
        }
        return null;
    }


    @Override
    public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException,
            ServletException {

        RequestProperties props = getRequestProperties(request);

        if (!verifyPermissions(props, response)) {
            return;
        }

        applyCacheHeaders(props, response);

        // when the URL is /confluence/plugins/servlet/<appPrefix>
        // we need to redirect to /confluence/plugins/servlet/<appPrefix>/
        // (note the trailing slash). Otherwise loading resources will not
        // work.
        if ("index.html".equals(props.getLocalPath())) {
            if ("".equals(props.getUrlLocalPart())) {
                response.sendRedirect(request.getRequestURI() + "/");
                return;
            }
        }

        response.setContentType(props.getContentType());

        if (!sendOutput(props, response)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }


    protected RequestProperties getRequestProperties(HttpServletRequest request) {
        return new RequestProperties(this, request);
    }


    protected boolean verifyPermissions(RequestProperties props, HttpServletResponse response) throws IOException {
        return true;
    }


    protected void applyCacheHeaders(RequestProperties props, HttpServletResponse response) {
        if (props.shouldCache()) {
            response.setHeader("Cache-Control", "public,max-age=31536000");
        } else {
            response.setHeader("Cache-Control", "no-cache,must-revalidate");
        }
    }


    protected boolean sendOutput(RequestProperties props, HttpServletResponse response) throws IOException {

        if (this.messageBundleProvider != null && this.messageBundleProvider.isMessageBundle(props)) {
            response.setContentType(this.messageBundleProvider.getContentType());
            String bundleData = this.messageBundleProvider.loadBundle(props);
            response.getOutputStream().write(bundleData.getBytes(StandardCharsets.UTF_8));
            return true;
        }

        InputStream resource = getPluginResource(props.getLocalPath());
        if (resource == null) {
            return false;
        }

        try (resource) {
            String shortType = StringUtils.substringBefore(props.getContentType(), ";");
            if (VELOCITY_TYPES.contains(shortType)) {
                String template = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                String result = renderVelocity(template, props);

                if ("index.html".equals(props.getLocalPath())) {
                    result = prepareIndexHtml(result, props);
                }

                response.getOutputStream().write(result.getBytes(StandardCharsets.UTF_8));

            } else {
                resource.transferTo(response.getOutputStream());
            }
        }

        return true;
    }


    protected InputStream getPluginResource(String localPath) throws IOException {
        if (isDevMode()) {
            InputStream dev = loadFromDevelopmentDirectory(getPluginResourcePath(localPath));
            if (dev != null) {
                return dev;
            }
        }

        return getClass().getClassLoader().getResourceAsStream(getPluginResourcePath(localPath));
    }


    /**
     * @return true if the host application is running in development mode.
     */
    protected abstract boolean isDevMode();


    protected InputStream loadFromDevelopmentDirectory(String localPath) throws IOException {
        InputStream fileIn = null;
        String resourceDirectoryPaths = System.getProperty(Keys.SPARK_DEV_RESOURCE_DIRECTORIES);

        if (resourceDirectoryPaths == null) {
            return null;
        }

        for (String resourceDirectoryPath : StringUtils.split(resourceDirectoryPaths, ",")) {
            resourceDirectoryPath = resourceDirectoryPath.trim();
            resourceDirectoryPath = StringUtils.removeEnd(resourceDirectoryPath, "/") + "/";
            File resourceDirectory = new File(resourceDirectoryPath);

            if (resourceDirectory.isDirectory()) {
                File resource = new File(resourceDirectoryPath + localPath);
                if (resource.canRead()) {
                    fileIn = Files.newInputStream(resource.toPath());
                    break;
                }
            }
        }

        return fileIn;
    }


    abstract protected String renderVelocity(String template, RequestProperties props) throws IOException;


    abstract protected String prepareIndexHtml(String indexHtml, RequestProperties props) throws IOException;


    protected String getPluginResourcePath(String localPath) {
        return resourcePath + localPath;
    }

}
