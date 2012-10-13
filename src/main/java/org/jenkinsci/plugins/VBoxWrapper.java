package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStep;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.tasks.Builder;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

/**
 * Sample {@link Builder}.
 * 
 * <p>
 * When the user configures the project and enables this builder,
 * {@link DescriptorImpl#newInstance(StaplerRequest)} is invoked and a new
 * {@link VBoxWrapper} is created. The created instance is persisted to the
 * project configuration XML by using XStream, so this allows you to use
 * instance fields (like {@link #name}) to remember the configuration.
 * 
 * <p>
 * When a build is performed, the
 * {@link #perform(AbstractBuild, Launcher, BuildListener)} method will be
 * invoked.
 * 
 * @author Kohsuke Kawaguchi
 */

@SuppressWarnings("rawtypes")
public class VBoxWrapper extends BuildWrapper {

	private final ArrayList<BuildStep> vboxSetupSteps;
	private final ArrayList<BuildStep> vboxTeardownSteps;

	// Fields in config.jelly must match the parameter names in the
	// "DataBoundConstructor"
	@DataBoundConstructor
	public VBoxWrapper(ArrayList<BuildStep> vboxSetupSteps,
			ArrayList<BuildStep> vboxTeardownSteps) {
		this.vboxSetupSteps = vboxSetupSteps;
		this.vboxTeardownSteps = vboxTeardownSteps;
	}

	public ArrayList<BuildStep> getVboxSetupSteps() {
		return vboxSetupSteps;
	}

	public ArrayList<BuildStep> getVboxTeardownSteps() {
		return vboxTeardownSteps;
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
			
			invokeBuildSteps(build, launcher, vboxTeardownSteps, listener);
			return true;
		}

	}
	

	/*
	 * Default impl
	 * 
	 * @see hudson.tasks.BuildWrapper#setUp(hudson.model.AbstractBuild,
	 * hudson.Launcher, hudson.model.BuildListener)
	 */
	@Override
	public Environment setUp(AbstractBuild build, Launcher launcher,
			BuildListener listener) throws IOException, InterruptedException {

		invokeBuildSteps(build, launcher, vboxSetupSteps, listener);
		return new VBoxEnvironment(launcher);
	}

	private void invokeBuildSteps(AbstractBuild build, Launcher launcher, ArrayList<BuildStep> buildSteps, BuildListener listener) 
			throws InterruptedException, IOException {
		if (buildSteps == null) {
			listener.getLogger().println("No build steps declared");
			return;
		}
		
		for (BuildStep bs : buildSteps) {
			listener.getLogger().format("Invoking prebuild step '%s'\n", getBuildStepName(bs));
            if (!bs.prebuild(build,listener)) {
            	listener.getLogger().println(MessageFormat.format("{0} : {1} failed", build.toString(), getBuildStepName(bs)));
                return;
            }
		}
		
		for (BuildStep bs : buildSteps) {
			listener.getLogger().format("Invoking build step '%s'\n", getBuildStepName(bs));
			bs.perform(build, launcher, listener);
		}
	}
	
	private String getBuildStepName(BuildStep bs) {
		if (bs instanceof Describable<?>) {
             return ((Describable<?>) bs).getDescriptor().getDisplayName();
         } else {
             return bs.getClass().getSimpleName();
         }

     }
    
	@Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }
    
	/**
	 * Descriptor for {@link VBoxWrapper}. Used as a singleton. The class is
	 * marked as public so that it can be accessed from views.
	 */
	@Extension
	public static final class DescriptorImpl extends BuildWrapperDescriptor {

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
