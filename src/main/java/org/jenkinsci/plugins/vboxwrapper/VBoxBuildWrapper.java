package org.jenkinsci.plugins.vboxwrapper;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.slaves.OfflineCause;
import hudson.slaves.SlaveComputer;
import hudson.tasks.*;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/**
 * BuildWrapper
 * <p/>
 * <p/>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link VBoxBuildWrapper} is created. The created instance is persisted to the
 * project configuration XML by using XStream, so this allows you to use
 * instance fields to remember the configuration.
 *
 * @author theirix
 */

@SuppressWarnings("rawtypes")
public class VBoxBuildWrapper extends BuildWrapper {

    /* Initial timeout */
    private static final int CONNECT_TIMEOUT = 45;

    /* Total timeout */
    private static final int CONNECT_TOTAL_TIMEOUT = 3 * 60;

    /* Jelly bindings */
    private final List<String> virtualSlaves;
    private final boolean useSetup;
    private final boolean useTeardown;

    @DataBoundConstructor
    public VBoxBuildWrapper(List<String> virtualSlaves, boolean useSetup,
                            boolean useTeardown) {
        this.virtualSlaves = virtualSlaves;
        this.useSetup = useSetup;
        this.useTeardown = useTeardown;
    }

    public List<String> getVirtualSlaves() {
        return virtualSlaves;
    }

    public boolean isUseSetup() {
        return useSetup;
    }

    public boolean isUseTeardown() {
        return useTeardown;
    }

    /**
     * Custom environment that launches teardown steps
     */
    class VBoxEnvironment extends Environment {

        private final Launcher launcher;

        public VBoxEnvironment(final Launcher launcher) {
            this.launcher = launcher;
        }

        /**
         * Teardown build wrapper environment.
         * Turn off virtual machines
         */
        @Override
        public boolean tearDown(AbstractBuild build, BuildListener listener)
                throws IOException, InterruptedException {

            if (isUseTeardown()) {
                disconnectSlaves(listener);
                invokeVBoxCommand(getDescriptor().getTeardownCommand(), build,
                        launcher, listener);
            }

            return true;
        }

    }

    /**
     * Setup build wrapper environment.
     * It is the time to set up machines
     */
    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher,
                             BuildListener listener) throws IOException, InterruptedException {

        dumpSettings(listener);

        if (isUseSetup()) {
            invokeVBoxCommand(getDescriptor().getSetupCommand(), build,
                    launcher, listener);
            connectSlaves(listener);
        }

        return new VBoxEnvironment(launcher);
    }

    /**
     * Invoke shell command on master to setup/teardown selected virtual machines
     *
     * @param body actual command to execute, parameters are appended
     */
    private void invokeVBoxCommand(String body, AbstractBuild build,
                                   Launcher launcher, BuildListener listener)
            throws InterruptedException {
        if (body == null || body.equals(""))
            return;

        StringBuilder sb = new StringBuilder(body);
        for (String slave : getVirtualSlaves()) {
            sb.append(" ");
            sb.append(slave);
        }
        String commandLine = sb.toString();
        listener.getLogger().println("Expect to launch command " + commandLine);

        boolean isWindows = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
        CommandInterpreter interpreter = isWindows ? new BatchFile(commandLine) : new Shell(commandLine);
        if (!interpreter.perform(build, launcher, listener))
            listener.error("VBox setup shell failed");
    }

    private void dumpSettings(BuildListener listener) {
        listener.getLogger().format("useSetup %b\n", isUseSetup());
        listener.getLogger().format("useTeardown %b\n", isUseTeardown());
        listener.getLogger().format("setup command %s\n",
                getDescriptor().getSetupCommand());
        listener.getLogger().format("teardown command %s\n",
                getDescriptor().getTeardownCommand());
    }


    /**
     * Find slave computers with given status
     *
     * @return list of slave computers
     * @throws IOException
     */
    private ArrayList<SlaveComputer> getSlaveComputers(boolean collectOffline) throws IOException {
        ArrayList<SlaveComputer> computers = new ArrayList<SlaveComputer>();
        for (String slave : getVirtualSlaves()) {
            Computer computer = Jenkins.getInstance().getComputer(slave);
            if (computer == null) {
                throw new IOException("Cannot find registered slave "
                        + slave);
            }
            if (computer instanceof SlaveComputer && computer.isOffline() == collectOffline) {
                computers.add((SlaveComputer) computer);
            }
        }
        return computers;
    }

    /**
     * Run tasks in parallel
     * Returns if all callables are evaluated to true
     *
     * @throws IOException if any callable failed
     */
    private void executeTasks(ArrayList<SlaveComputer> computers,
                              List<Callable<Boolean>> callables, final BuildListener listener)
            throws IOException {        /* Start tasks */
        ExecutorService es = Executors.newFixedThreadPool(computers.size());
        try {
            List<Future<Boolean>> futures = es.invokeAll(callables);
            if (!futureAll(futures)) {
                throw new IOException("Some slaves are still in a previous state");
            }
        } catch (Exception e) {
            throw new IOException("Node waiting failed", e);
        }
        listener.getLogger().format("Successfully awaited %d slaves\n", computers.size());
    }

    /**
     * Disconnect all specified in settings slaves
     * Postcondition: all slaves are offline or an exception thrown
     */
    private void disconnectSlaves(final BuildListener listener) throws IOException {

		/* Collect online slaves */
        ArrayList<SlaveComputer> computers = getSlaveComputers(false);
        if (computers.isEmpty())
            return;

        List<Callable<Boolean>> callables = new ArrayList<Callable<Boolean>>();
        for (final SlaveComputer computer : computers) {
            callables.add(new Callable<Boolean>() {
                /**
                 * Slave reconnect attempts
                 *
                 * @return does a slave become online
                 */
                public Boolean call() throws Exception {
					
					/* Disconnect at first */
                    synchronized (listener) {
                        listener.getLogger().format("Disconnecting slave %s\n",
                                computer.getName());
                    }

                    Future future = computer.disconnect(new OfflineCause.ByCLI("disconnect to connect"));
                    try {
                        future.get(CONNECT_TIMEOUT, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        synchronized (listener) {
                            listener.getLogger()
                                    .format("Disconnect timed out or failed: %s\n", e.getMessage());
                        }
                    }

                    return true;
                }

            });
        }

        executeTasks(computers, callables, listener);
    }


    /**
     * Await all specified in settings slaves to became online
     * Postcondition: all slaves are online or an exception thrown
     *
     * @throws IOException is thrown if any slave is still offline
     */
    private void connectSlaves(final BuildListener listener) throws IOException {

		/* Collect offline slaves */
        ArrayList<SlaveComputer> computers = getSlaveComputers(true);
        if (computers.isEmpty())
            return;

        List<Callable<Boolean>> callables = new ArrayList<Callable<Boolean>>();
        for (final SlaveComputer computer : computers) {
            callables.add(new Callable<Boolean>() {
                /**
                 * Slave reconnect attempts
                 *
                 * @return does a slave become online
                 */
                public Boolean call() throws Exception {
					
					/* Disconnect at first */
                    synchronized (listener) {
                        listener.getLogger().format("Disconnecting slave %s at first\n",
                                computer.getName());
                    }

                    Future future = computer.disconnect(new OfflineCause.ByCLI("disconnect to connect"));
                    try {
                        future.get(CONNECT_TIMEOUT, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        synchronized (listener) {
                            listener.getLogger()
                                    .format("Disconnect timed out or failed: %s\n", e.getMessage());
                        }
                    }
                    int total_elapsed = 0;
                    int retry = 0;
                    while (total_elapsed < CONNECT_TOTAL_TIMEOUT) {

                        if (computer.isOnline())
                            return true;

                        synchronized (listener) {
                            listener.getLogger().format("Reconnecting to %s, try %d...\n",
                                    computer.getName(), retry + 1);
                        }
                        long elapsed = System.currentTimeMillis();
                        future = computer.connect(false);
                        try {
                            future.get(CONNECT_TIMEOUT, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            synchronized (listener) {
                                listener.getLogger().format("Connect timed out or failed: %s\n",
                                        e.getMessage());
                            }
                        }
						/* Who knows, how threads are measured */
                        elapsed = Math.max(1, System.currentTimeMillis() - elapsed) / 1000;
                        total_elapsed += elapsed;
                        ++retry;
                    }
                    return computer.isOnline();
                }

            });
        }

        executeTasks(computers, callables, listener);
    }

    /**
     * Returns true if all boolean futures evaluates to true
     * Waits for all of them before return
     *
     * @param futures to reduce
     * @return logical and of future results
     */
    private Boolean futureAll(final Collection<Future<Boolean>> futures)
            throws InterruptedException, ExecutionException {
        boolean result = true;
        for (Future<Boolean> future : futures) {
            result = result && future.get();
        }
        return result;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    /**
     * Descriptor for {@link VBoxBuildWrapper}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     */
    @Extension
    public static final class DescriptorImpl extends BuildWrapperDescriptor {

        private String setupCommand;
        private String teardownCommand;

        public DescriptorImpl() {
            super();
            load();
        }

        public String getSetupCommand() {
            return setupCommand;
        }

        public String getTeardownCommand() {
            return teardownCommand;
        }

        /**
         * Load a descriptor from json and saves global settings.
         */
        @Override
        public boolean configure(StaplerRequest req, JSONObject json)
                throws FormException {
            setupCommand = json.getString("setupCommand");
            teardownCommand = json.getString("teardownCommand");
            save();
            return super.configure(req, json);
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "VirtualBox setup/teardown tasks";
        }

        @Override
        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }

    }
}
