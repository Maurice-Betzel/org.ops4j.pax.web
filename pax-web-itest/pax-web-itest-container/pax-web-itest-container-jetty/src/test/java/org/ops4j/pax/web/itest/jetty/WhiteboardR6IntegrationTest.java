package org.ops4j.pax.web.itest.jetty;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.inject.Inject;
import javax.servlet.AsyncContext;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletRequestEvent;
import javax.servlet.ServletRequestListener;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.util.Filter;
import org.ops4j.pax.web.service.WebContainer;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.context.ServletContextHelper;

@RunWith(PaxExam.class)
public class WhiteboardR6IntegrationTest extends ITestBase {

    @Inject
    @Filter(timeout = 20000)
    private WebContainer webcontainer;

    @Inject
    private BundleContext bundleContext;

    @Configuration
    public static Option[] configure() {
        return configureJetty();
    }

    @Before
    public void setUp() throws BundleException, InterruptedException {

    }

    @After
    public void tearDown() throws BundleException {
    }

    @Test
    public void testWhiteBoardServlet() throws Exception {
        ServiceRegistration<Servlet> registerService = registerServlet();

        testClient.testWebPath("http://127.0.0.1:8181/myservlet", "Servlet name: value");

        registerService.unregister();
    }

    @Test
    @Ignore("Registration of ServletContextHelper isn't functional right now")
    public void testWhiteBoardServletWithContext() throws Exception {
        Dictionary<String, String> contextProps = new Hashtable<>();
        contextProps.put("osgi.http.whiteboard.context.name", "my-context");
        contextProps.put("osgi.http.whiteboard.context.path", "/myapp");

        ServiceRegistration<ServletContextHelper> contextHelperService = bundleContext
                .registerService(ServletContextHelper.class, new CDNServletContextHelper(), contextProps);

        Dictionary<String, String> extProps = new Hashtable<>();
        extProps.put("osgi.http.whiteboard.context.select", "(osgi.http.whiteboard.context.name=my-context)");
        ServiceRegistration<Servlet> registerServlet = registerServlet(extProps);

        testClient.testWebPath("http://127.0.0.1:8181/myapp/myservlet", "Servlet name: value");

        registerServlet.unregister();
        contextHelperService.unregister();

    }

    @Test
    @Ignore("The whiteboard Extender doesn't support this right now")
    public void testErrorServlet() throws Exception {
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put("osgi.http.whiteboard.servlet.errorPage", "java.io.IOException");
        properties.put("osgi.http.whiteboard.servlet.errorPage", "500");

        ServiceRegistration<Servlet> registerService = bundleContext.registerService(Servlet.class,
                new MyErrorServlet(), properties);

        testClient.testWebPath("http://127.0.0.1:8181/error", "Error Servlet, we do have a 500", 500, false);

        registerService.unregister();
    }

    @Test
    @Ignore("Fails right now ... ")
    public void testAsyncServlet() throws Exception {
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put("osgi.http.whiteboard.servlet.pattern", "/as");
        properties.put("osgi.http.whiteboard.servlet.asyncSupported", "true");

        ServiceRegistration<Servlet> registerService = bundleContext.registerService(Servlet.class, new AsyncServlet(),
                properties);

        testClient.testAsyncWebPath("http://127.0.0.1:8181/as", "Servlet executed async in:", 400, false, null);

        registerService.unregister();
    }

    @Test
    @Ignore("cuases a server restart, which results in a duplicate reservation of the servlet")
    public void testFilterServlet() throws Exception {
        ServiceRegistration<Servlet> registerService = registerServlet();

        Dictionary<String, String> properties = new Hashtable<>();
        properties.put("osgi.http.whiteboard.filter.pattern", "/*");
        ServiceRegistration<javax.servlet.Filter> registerFilter = bundleContext
                .registerService(javax.servlet.Filter.class, new MyFilter(), properties);

        testClient.testWebPath("http://127.0.0.1:8181/myservlet", "before");

        registerFilter.unregister();
        registerService.unregister();
    }
    
    @Test
    public void testListeners() throws Exception {
        ServiceRegistration<Servlet> registerService = registerServlet();

        MyServletRequestListener listener = new MyServletRequestListener();
        
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put("osgi.http.whiteboard.listener", "true");

        ServiceRegistration<ServletRequestListener> listenerService = bundleContext.registerService(ServletRequestListener.class, listener, properties);
        
        testClient.testWebPath("http://127.0.0.1:8181/myservlet", "Servlet name: value");
        
        assertThat(listener.gotEvent(), is(true));
        
        listenerService.unregister();
        registerService.unregister();
    }

    private ServiceRegistration<Servlet> registerServlet() {
        return registerServlet(null);
    }

    private ServiceRegistration<Servlet> registerServlet(Dictionary<String, String> extendedProps) {
        Dictionary<String, String> properties = new Hashtable<>();
        properties.put("osgi.http.whiteboard.servlet.pattern", "/myservlet");
        properties.put("servlet.init.myname", "value");

        if (extendedProps != null) {
            Enumeration<String> keys = extendedProps.keys();
            while (keys.hasMoreElements()) {
                String key = keys.nextElement();
                properties.put(key, extendedProps.get(key));
            }
        }

        ServiceRegistration<Servlet> registerService = bundleContext.registerService(Servlet.class, new MyServlet(),
                properties);
        return registerService;
    }

    public class CDNServletContextHelper extends ServletContextHelper {
        public URL getResource(String name) {
            try {
                return new URL("http://acmecdn.com/myapp/" + name);
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }

    public class MyServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        private String name = "<not set>";

        public void init(ServletConfig config) {
            name = config.getInitParameter("myname");
        }

        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setContentType("text/plain");
            resp.getWriter().println("Servlet name: " + name);
        }
    }

    public class MyErrorServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
            resp.setContentType("text/plain");
            resp.getWriter().println("Error Servlet, we do have a 500");
        }
    }

    public class AsyncServlet extends HttpServlet {
        private static final long serialVersionUID = 1L;

        ExecutorService executor = Executors.newCachedThreadPool(r -> new Thread(r, "Pooled Thread"));

        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            doGetAsync(req.startAsync());
        }

        private void doGetAsync(AsyncContext asyncContext) {
            executor.submit(() -> {
                try {
                    PrintWriter writer = asyncContext.getResponse().getWriter();
                    writer.print("Servlet executed async in: " + Thread.currentThread()); // writes
                                                                                          // 'Pooled
                                                                                          // Thread'
                } finally {
                    asyncContext.complete();
                }
                return null;
            });
        }
    }

    public class MyFilter implements javax.servlet.Filter {
        public void init(FilterConfig filterConfig) throws ServletException {
        }

        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            response.getWriter().write("before");
            chain.doFilter(request, response);
            response.getWriter().write("after");
        }

        public void destroy() {
        }
    }

    public class MyServletRequestListener implements ServletRequestListener {
        
        private boolean event = false;
        
        public void requestInitialized(ServletRequestEvent sre) {
            event = true;
            System.out.println("Request initialized for client: " + sre.getServletRequest().getRemoteAddr());
        }

        public void requestDestroyed(ServletRequestEvent sre) {
            System.out.println("Request destroyed for client: " + sre.getServletRequest().getRemoteAddr());
        }
        
        public boolean gotEvent() {
            return event;
        }
    }

}