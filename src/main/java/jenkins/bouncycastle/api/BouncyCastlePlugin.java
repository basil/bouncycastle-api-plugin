package jenkins.bouncycastle.api;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.Provider;
import java.security.Security;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.accmod.restrictions.suppressions.SuppressRestrictedWarnings;
import hudson.Main;
import hudson.Plugin;
import hudson.PluginWrapper;
import hudson.remoting.Which;
import java.io.IOException;
import jenkins.model.Jenkins;
import jenkins.util.AntClassLoader;
import jenkins.util.URLClassLoader2;

@SuppressWarnings("deprecation") // there is no other way to achieve this at the correct lifecycle point
@Restricted(NoExternalUse.class) // just for Jenkins access not part of the API
public class BouncyCastlePlugin extends Plugin {

    private static final Logger LOG = Logger.getLogger(BouncyCastlePlugin.class.getName());

    private static final boolean isActive;

    static {
        // BouncyCastle FIPS is installed in the JVM, we can not install over the top of it so do not try
        Provider p = Security.getProvider("BCFIPS");
        isActive = (p == null);

        LOG.log(Level.CONFIG,
                isActive ? "BouncyCastle Providers from BouncyCastle API plugin will be active" :
                           "Detected the presence of the BouncyCastle FIPS provider, the regular BouncyCastle JARs will not be available.");
    }

    @Override
    @SuppressRestrictedWarnings({jenkins.util.AntClassLoader.class, jenkins.util.URLClassLoader2.class}) // we are messing with the classloader
    public void start() throws Exception {
        if (!isActive) {
            // Alternative BouncyCastle is installed do no not insert these libraries
            return;
        }
        // this is the hairy part.
        // add the BouncyCastle APIs into the classpath for other plugins (and this plugin to use!)
        /*
         * Whilst plugins that have code may go boom before this with class loading issues, extensions (at this point)
         * have not been discovered, so this would only affect people using the deprecated `Plugin` class (like we are!)
         */

        final File optionalLibDir = getOptionalLibDirectory();
        File[] optionalLibs = optionalLibDir.listFiles();

        if (optionalLibs == null || optionalLibs.length == 0) {
            if (Main.isUnitTest) {
                LOG.log(Level.INFO, "{0} not found; for non RealJenkinsRule this is fine and can be ignored.", optionalLibDir);
            } else {
                LOG.log(Level.WARNING, "No optional-libs not found at {0}, BouncyCastle APIs will be unavailable causing strange runtime issues.", optionalLibDir);
                // fail fast, most likely a packaging issue
                throw new IllegalStateException("BouncyCastle libs are missing from WEB-INF/optional-libs");
            }
        } else {
            ClassLoader cl = this.getWrapper().classLoader;

            for (File optionalLib : optionalLibs) {
                LOG.log(Level.CONFIG, () -> "Inserting " + optionalLib + " into bouncycastle-api plugin classpath");
                if (cl instanceof AntClassLoader) {
                    ((AntClassLoader) cl).addPathComponent(optionalLib);
                } else if (cl instanceof URLClassLoader2) {
                    ((URLClassLoader2) cl).addURL(optionalLib.toURI().toURL());
                } else if (cl instanceof URLClassLoader) {
                    Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                    addURL.setAccessible(true);
                    addURL.invoke(cl, optionalLib.toURI().toURL());
                } else {
                    throw new IllegalStateException("Unexpected class loader: " + cl);
                }
            }
        }
        SecurityProviderInitializer.addSecurityProvider();
    }

    public static boolean isActive() {
        return isActive;
    }


    private final File getOptionalLibDirectory() throws IOException {
        PluginWrapper pw = getWrapper();
        File explodedPluginsDir = pw.parent.getWorkDir();
        if (explodedPluginsDir == null) {
            // not overridden use default of ${JENKINS_HOME}/plugins
            explodedPluginsDir = new File(Jenkins.get().getRootDir(), "plugins");
            LOG.log(Level.FINE, "plugindir not specified, falling back to $'{'JENKINS_HOME/plugins'}' as {0}", explodedPluginsDir);
        }
        File f =  new File(explodedPluginsDir, pw.getShortName() + "/WEB-INF/optional-lib/");
        if (!f.isDirectory()) {
            File here = Which.jarFile(BouncyCastlePlugin.class);
            File alt = new File(here.getParentFile().getParentFile(), "optional-lib");
            if (alt.isDirectory()) {
                LOG.log(Level.FINE, "fallback based on {0}", here);
                f = alt;
            }
        }
        LOG.log(Level.FINE, "using {0} as the optional-lib directory", f);
        return f;
    }
}
