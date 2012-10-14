package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.TaskListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Node;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Shell;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

		/*
		 * @see
		 * hudson.tasks.BuildWrapper.Environment#tearDown(hudson.model.AbstractBuild
		 * , hudson.model.BuildListener)
		 */
		@Override
		public boolean tearDown(AbstractBuild build, BuildListener listener)
				throws IOException, InterruptedException {

			if (isUseTeardown())
				invokeVBoxCommand(getDescriptor().getTeardownCommand(), build, launcher, listener);
			return true;
		}

	}

	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {

		dumpSettings(listener);
		
		if (isUseSetup())
			invokeVBoxCommand(getDescriptor().getSetupCommand(), build, launcher, listener);
		
		return new VBoxEnvironment(launcher);
	}
	
	/*
	 * Invoke shell command on master to setup/teardown selected virtual machines
	 */
	private void invokeVBoxCommand (String body, AbstractBuild build, Launcher launcher, BuildListener listener) throws InterruptedException {
		if (body == null || body.equals(""))
			return;
		
		StringBuilder sb = new StringBuilder(body);
		for (String slave: getVirtualSlaves()) {
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
		listener.getLogger().format("setup command %s\n", getDescriptor().getSetupCommand());
		listener.getLogger().format("teardown command %s\n", getDescriptor().getTeardownCommand());
	}

	/*private void invokeBuildSteps(AbstractBuild build, Launcher launcher,
			ArrayList<BuildStep> buildSteps, BuildListener listener)
			throws InterruptedException, IOException {

		if (virtualSlaves != null) {
			for (String slaveName : virtualSlaves) {
				listener.getLogger().format("Using slave: %s\n", slaveName);
			}
		}

		if (buildSteps == null) {
			listener.getLogger().println("No build steps declared");
			return;
		}

		for (BuildStep bs : buildSteps) {
			listener.getLogger().format("Invoking prebuild step '%s'\n",
					getBuildStepName(bs));
			if (!bs.prebuild(build, listener)) {
				listener.getLogger().println(
						MessageFormat.format("{0} : {1} failed",
								build.toString(), getBuildStepName(bs)));
				return;
			}
		}

		for (BuildStep bs : buildSteps) {
			listener.getLogger().format("Invoking build step '%s'\n",
					getBuildStepName(bs));
			bs.perform(build, launcher, listener);
		}
	}

	private String getBuildStepName(BuildStep bs) {
		if (bs instanceof Describable<?>) {
			return ((Describable<?>) bs).getDescriptor().getDisplayName();
		} else {
			return bs.getClass().getSimpleName();
		}

	}*/

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
