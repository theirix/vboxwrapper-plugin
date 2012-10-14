package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.Node;
import hudson.slaves.SlaveComputer;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Shell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * BuildWrapper
 * 
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link VBoxWrapper} is created. The created instance is persisted to the
 * project configuration XML by using XStream, so this allows you to use
 * instance fields (like {@link #name}) to remember the configuration.
 * 
 * @author theirix
 */

@SuppressWarnings("rawtypes")
public class VBoxWrapper extends BuildWrapper {

	/* Initial timeout */
	private static final int CONNECT_INITIAL_TIMEOUT = 10;

	/* Retry count to connect to the slave */
	private static final int CONNECT_RETRY_COUNT = 10;
	
	/* Jelly bindings */
	private final List<String> virtualSlaves;
	private final boolean useSetup;
	private final boolean useTeardown;

	@DataBoundConstructor
	public VBoxWrapper(List<String> virtualSlaves, boolean useSetup,
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

		public VBoxEnvironment(Launcher launcher) {
			this.launcher = launcher;
		}

		/**
		 * Teardown build wrapper environment.
		 * Turn off virtual machines
		 */
		@Override
		public boolean tearDown(AbstractBuild build, BuildListener listener)
				throws IOException, InterruptedException {

			if (isUseTeardown())
				invokeVBoxCommand(getDescriptor().getTeardownCommand(), build,
						launcher, listener);
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
			awaitNodes(listener);
		}

		return new VBoxEnvironment(launcher);
	}

	/**
	 * Invoke shell command on master to setup/teardown selected virtual machines
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
		Shell shell = new Shell(commandLine);
		if (!shell.perform(build, launcher, listener))
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
	 * Await all specified in settings nodes to became online
	 * Postcondition: all nodes are online
	 * 
	 * @throws IOException is thrown if any of nodes is still offline 
	 */
	private void awaitNodes(final BuildListener listener) throws IOException {

		/* Collect offline nodes */
		ArrayList<SlaveComputer> computers = new ArrayList<SlaveComputer>();
		for (String slave : getVirtualSlaves()) {
			Computer computer = Jenkins.getInstance().getComputer(slave);
			if (computer == null) {
				throw new IOException("Cannot find registered slave "
						+ slave);
			}
			if (computer instanceof SlaveComputer && computer.isOffline()) {
				computers.add((SlaveComputer) computer);
			}
		}
		
		if (computers.isEmpty())
			return;

		List<Callable<Boolean>> callables = new ArrayList<Callable<Boolean>>();
		for (final SlaveComputer computer : computers) {
			callables.add(new Callable<Boolean>() {
				/**
				 * Slave reconnect attempts
				 * 
				 * @return does a slave became online
				 */
				public Boolean call() throws Exception {
					/* Timeout in seconds */
					int timeout = CONNECT_INITIAL_TIMEOUT;
					for (int retry = 0; retry < CONNECT_RETRY_COUNT; ++retry) {

						if (computer.isOnline())
							return true;

						synchronized (listener) {
							listener.getLogger().format(
									"Reconnecting to %s, try %d of %d...\n",
									computer.getName(), retry+1, CONNECT_RETRY_COUNT);
						}
						Future future = computer.connect(true);
						try {
							future.get(timeout, TimeUnit.SECONDS);
						} catch (Exception e) {
							synchronized (listener) {
								listener.getLogger()
										.format("Connect timed out or failed: %s\n",e.getMessage());
							}
						}
						
						timeout *= 1.2;
					}
					return false;
				}

			});
		}

		/* Start tasks */
		ExecutorService es = Executors.newFixedThreadPool(computers.size());
		try {
			List<Future<Boolean>> futures = es.invokeAll(callables);
			if (!futureAll(futures)) {
				throw new IOException("Some nodes are still offline");
			}
		} catch (Exception e) {
			throw new IOException("Node await failed", e);
		}
		listener.getLogger().format("Successfully awaited %d nodes\n", computers.size());
	}

	/**
	 * Returns true if all boolean futures evaluates to true
	 * Waits for all of them before return
	 * @param futures
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

	/**
	 * Finds all slave machine names
	 * @return list of names
	 */
	public static List<String> getSlaveNames() {
		final List<Node> nodes = Jenkins.getInstance().getNodes();
		List<String> test = new ArrayList<String>();

		for (Node node : nodes) {
			if (!node.getNodeName().equals("")
					&& !node.getNodeName().equals("master"))
				test.add(node.getNodeName());
		}
		return test;
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}

	/**
	 * Descriptor for {@link VBoxWrapper}. Used as a singleton. The class is
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
