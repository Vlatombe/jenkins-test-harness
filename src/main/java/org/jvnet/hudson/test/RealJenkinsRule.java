/*
 * The MIT License
 *
 * Copyright 2021 CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jvnet.hudson.test;

import hudson.ExtensionList;
import hudson.model.DownloadService;
import hudson.model.UpdateSite;
import hudson.util.StreamCopyThread;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Random;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;
import jenkins.model.Jenkins;
import jenkins.model.JenkinsLocationConfiguration;
import org.apache.commons.io.FileUtils;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.recipes.LocalData;

/**
 * Like {@link JenkinsSessionRule} but running Jenkins in a more realistic environment.
 * <p>Though Jenkins is run in a separate JVM using Winstone ({@code java -jar jenkins.war}),
 * you can still do “whitebox” testing: directly calling Java API methods, starting from {@link JenkinsRule} or not.
 * This is because the test code gets sent to the remote JVM and loaded and run there.
 * (Thus when using Maven, there are at least <em>three</em> JVMs involved:
 * Maven itself; the Surefire booter with your top-level test code; and the Jenkins controller with test bodies.)
 * Just as with {@link JenkinsRule}, all plugins found in the test classpath will be enabled,
 * but with more realistic behavior: class loaders in a graph, {@code pluginFirstClassLoader} and {@code maskClasses}, etc.
 * <p>“Compile-on-save” style development works for classes and resources in the current plugin:
 * with a suitable IDE, you can edit a source file, have it be sent to {@code target/classes/},
 * and rerun a test without needing to go through a full Maven build cycle.
 * This is because {@code target/test-classes/the.hpl} is used to load unpacked plugin resources.
 * <p>Like {@link JenkinsRule}, the controller is started in “development mode”:
 * the setup wizard is suppressed, the update center is not checked, etc.
 * <p>Known limitations:
 * <ul>
 * <li>Execution is a bit slower due to the overhead of launching a new JVM; and class loading overhead cannot be shared between test cases. More memory is needed.
 * <li>Remote thunks must be serializable. If they need data from the test JVM, you will need to create a {@code static} nested class to package that.
 * <li>{@code static} state cannot be shared between the top-level test code and test bodies (though the compiler will not catch this mistake).
 * <li>When using a snapshot dep on Jenkins core, you must build {@code jenkins.war} to test core changes (there is no “compile-on-save” support for this).
 * <li>{@link TestExtension} is not available.
 * <li>{@link LocalData} is not available.
 * <li>{@link LoggerRule} is not available.
 * <li>{@link BuildWatcher} is not available.
 * <li>There is no automatic test timeout.
 * <li>There is not currently a way to disable plugins.
 * <li>There is not currently a way to run multiple controllers in parallel or to run “blackbox” operations from the test JVM while the controller is running.
 * <li>There is not currently any flexibility in how the controller is launched (such as custom system properties).
 * <li>There is not yet a way to run the controller JVM in a debugger.
 * </ul>
 * <p>Systems not yet tested:
 * <ul>
 * <li>Possibly {@link Timeout} can be used.
 * <li>Possibly {@link ExtensionList#add(Object)} can be used as an alternative to {@link TestExtension}.
 * <li>It is unknown whether plugin-to-plugin snapshot dependencies support cross-plugin “compile-on-save” mode.
 * </ul>
 */
public final class RealJenkinsRule implements TestRule {

    private static final Logger LOGGER = Logger.getLogger(JenkinsSessionRule.class.getName());

    private Description description;

    private final TemporaryDirectoryAllocator tmp = new TemporaryDirectoryAllocator();

    /**
     * JENKINS_HOME dir, consistent across restarts.
     */
    private File home;

    /**
     * TCP/IP port that the server is listening on.
     * Like the home directory, this will be consistent across restarts.
     */
    private int port;

    @Override public Statement apply(final Statement base, Description description) {
        this.description = description;
        return new Statement() {
            @Override public void evaluate() throws Throwable {
                System.out.println("=== Starting " + description);
                try {
                    home = tmp.allocate();
                    File initGroovyD = new File(home, "init.groovy.d");
                    initGroovyD.mkdir();
                    // TODO perhaps do this with a tiny custom plugin rather than a Groovy init script
                    // (this could also use a simple HTTP API rather than *.ser files, making it easier to implement a separate runRemotely step)
                    FileUtils.copyURLToFile(RealJenkinsRule.class.getResource("RealJenkinsRuleInit.groovy"), new File(initGroovyD, "RealJenkinsRuleInit.groovy"));
                    port = new Random().nextInt(16384) + 49152; // https://en.wikipedia.org/wiki/List_of_TCP_and_UDP_port_numbers#Dynamic,_private_or_ephemeral_ports
                    File plugins = new File(home, "plugins");
                    plugins.mkdir();
                    // Adapted from UnitTestSupportingPluginManager:
                    URL u = RealJenkinsRule.class.getResource("/the.jpl");
                    if (u == null) {
                        u = RealJenkinsRule.class.getResource("/the.hpl");
                    }
                    if (u != null) {
                        String thisPlugin;
                        try (InputStream is = u.openStream()) {
                            thisPlugin = new Manifest(is).getMainAttributes().getValue("Short-Name");
                        }
                        if (thisPlugin == null) {
                            throw new IOException("malformed " + u);
                        }
                        // Not totally realistic, but test phase is run before package phase. TODO can we add an option to run in integration-test phase?
                        FileUtils.copyURLToFile(u, new File(plugins, thisPlugin + ".jpl"));
                    }
                    URL index = RealJenkinsRule.class.getResource("/test-dependencies/index");
                    if (index != null) {
                        try (BufferedReader r = new BufferedReader(new InputStreamReader(index.openStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = r.readLine()) != null) {
                                final URL url = new URL(index, line + ".jpi");
                                File f;
                                try {
                                    f = new File(url.toURI());
                                } catch (IllegalArgumentException x) {
                                    if (x.getMessage().equals("URI is not hierarchical")) {
                                        throw new IOException(
                                                "You are probably trying to load plugins from within a jarfile (not possible). If" +
                                                " you are running this in your IDE and see this message, it is likely" +
                                                " that you have a clean target directory. Try running 'mvn test-compile' " +
                                                "from the command line (once only), which will copy the required plugins " +
                                                "into target/test-classes/test-dependencies - then retry your test", x);
                                    } else {
                                        throw new IOException(index + " contains bogus line " + line, x);
                                    }
                                }
                                if (f.exists()) {
                                    FileUtils.copyURLToFile(url, new File(plugins, line + ".jpi"));
                                } else {
                                    FileUtils.copyURLToFile(new URL(index, line + ".hpi"), new File(plugins, line + ".jpi"));
                                }
                                // TODO add method to disable a plugin (e.g. to test optional dependencies)
                            }
                        }
                    }
                    base.evaluate();
                } finally {
                    try {
                        tmp.dispose();
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, null, x);
                    }
                }
            }
        };
    }

    /**
     * One step to run.
     * <p>Since this thunk will be sent to a different JVM, it must be serializable.
     * The test class will certainly not be serializable, so you cannot use an anonymous inner class.
     * If your thunk requires no parameters from the test JVM, the friendliest idiom is a static method reference:
     * <pre>
     * &#64;Test public void stuff() throws Throwable {
     *     rr.then(YourTest::_stuff);
     * }
     * private static void _stuff(JenkinsRule r) throws Throwable {
     *     // as needed
     * }
     * </pre>
     */
    @FunctionalInterface
    public interface Step extends Serializable {
        void run(JenkinsRule r) throws Throwable;
    }

    // TODO add methods for more advanced use cases (such as multiple Jenkins services interacting):
    // · withSession: run a Jenkins session, run a test thunk _locally_ (blackbox testing), shut down
    // · runRemotely: run a test thunk remotely, given a running session (whitebox testing)
    // Thus rr.then(s) would be shorthand for rr.withSession(() -> rr.runRemotely(s))

    /**
     * Run one Jenkins session, send a test thunk, and shut down.
     */
    public void then(Step s) throws Throwable {
        Body.writeSer(new File(home, "step.ser"), s);
        String cp = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(
                new File(System.getProperty("java.home"), "bin/java").getAbsolutePath(),
                "-Dhudson.Main.development=true",
                "-DRealJenkinsRule.location=" + RealJenkinsRule.class.getProtectionDomain().getCodeSource().getLocation(),
                "-DRealJenkinsRule.cp=" + cp,
                "-DRealJenkinsRule.port=" + port,
                "-DRealJenkinsRule.description=" + description,
                "-jar", WarExploder.findJenkinsWar().getAbsolutePath(),
                "--httpPort=" + port, "--httpListenAddress=127.0.0.1",
                "--prefix=/jenkins");
        System.out.println("Launching: " + pb.command().toString().replace(cp, "…"));
        pb.environment().put("JENKINS_HOME", home.getAbsolutePath());
        // TODO options to set env, Java options, Winstone options, etc.
        // TODO pluggable launcher interface to support a Dockerized Jenkins JVM
        // TODO if test JVM is running in a debugger, start Jenkins JVM in a debugger also
        Process proc = pb.start();
        // TODO prefix streams with per-test timestamps
        new StreamCopyThread(description.toString(), proc.getInputStream(), System.out).start();
        new StreamCopyThread(description.toString(), proc.getErrorStream(), System.err).start();
        if (proc.waitFor() != 0) {
            throw new AssertionError("nonzero exit code");
        }
        File error = new File(home, "error.ser");
        if (error.isFile()) {
            throw (Throwable) Body.readSer(error, null);
        }
    }

    // Should not refer to any types outside the JRE.
    public static final class Body {

        public static void run(Object jenkins) throws Exception {
            Object pluginManager = jenkins.getClass().getField("pluginManager").get(jenkins);
            ClassLoader uberClassLoader = (ClassLoader) pluginManager.getClass().getField("uberClassLoader").get(pluginManager);
            ClassLoader tests = new URLClassLoader(Stream.of(System.getProperty("RealJenkinsRule.cp").split(File.pathSeparator)).map(Body::pathToURL).toArray(URL[]::new), uberClassLoader);
            tests.setDefaultAssertionStatus(true);
            String home = System.getenv("JENKINS_HOME");
            Object s = readSer(new File(home, "step.ser"), tests);
            System.err.println("Running step: " + s);
            Object cjr = tests.loadClass("org.jvnet.hudson.test.RealJenkinsRule$CustomJenkinsRule").getConstructor(Object.class, int.class).newInstance(jenkins, Integer.getInteger("RealJenkinsRule.port"));
            Method run = tests.loadClass("org.jvnet.hudson.test.RealJenkinsRule$Step").getMethod("run", tests.loadClass("org.jvnet.hudson.test.JenkinsRule"));
            try {
                run.invoke(s, cjr);
            } catch (InvocationTargetException x) {
                // TODO use raw cause if it seems safe enough
                writeSer(new File(home, "error.ser"), new ProxyException(x.getCause()));
            }
            jenkins.getClass().getMethod("cleanUp").invoke(jenkins);
            ((AutoCloseable) cjr).close();
            System.exit(0);
        }

        private static URL pathToURL(String path) {
            try {
                return Paths.get(path).toUri().toURL();
            } catch (MalformedURLException x) {
                throw new IllegalArgumentException(x);
            }
        }

        static void writeSer(File f, Object o) throws Exception {
            try (OutputStream os = new FileOutputStream(f);
                    ObjectOutputStream oos = new ObjectOutputStream(os)) {
                oos.writeObject(o);
            }
        }

        static Object readSer(File f, ClassLoader loader) throws Exception {
            try (InputStream is = new FileInputStream(f);
                    ObjectInputStream ois = new ObjectInputStream(is) {
                @Override
                protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
                    if (loader != null) {
                        try {
                            return loader.loadClass(desc.getName());
                        } catch (ClassNotFoundException x) {
                        }
                    }
                    return super.resolveClass(desc);
                }
            }) {
                return ois.readObject();
            }
        }

        private Body() {}

    }

    public static final class CustomJenkinsRule extends JenkinsRule implements AutoCloseable {
        public CustomJenkinsRule(Object jenkins, int port) throws Exception {
            this.jenkins = (Jenkins) jenkins;
            localPort = port;
            // Stuff picked out of before(), configureUpdateCenter():
            JenkinsLocationConfiguration.get().setUrl(getURL().toString());
            this.jenkins.setNoUsageStatistics(true);
            DownloadService.neverUpdate = true;
            UpdateSite.neverUpdate = true;
            testDescription = Description.createSuiteDescription(System.getProperty("RealJenkinsRule.description"));
            env = new TestEnvironment(this.testDescription);
            env.pin();
        }
        @Override public void close() throws Exception {
            env.dispose();
        }
    }

    // Copied from hudson.remoting
    public static final class ProxyException extends IOException {
        public ProxyException(Throwable cause) {
            super(cause.toString());
            setStackTrace(cause.getStackTrace());
            if (cause.getCause() != null) {
                initCause(new ProxyException(cause.getCause()));
            }
            for (Throwable suppressed : cause.getSuppressed()) {
                addSuppressed(new ProxyException(suppressed));
            }
        }
        @Override public String toString() {
            return getMessage();
        }
    }

}
