package org.jenkinsci.plugins;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.ParameterValue;
import hudson.util.VariableResolver;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.ArrayList;
import java.util.List;


public class VBoxParameterValue extends ParameterValue {

    private static final long serialVersionUID = 1L;

    private final List<String> nodes;
    private final String nodeDelimiter;

    @DataBoundConstructor
    public VBoxParameterValue(String name, List<String> nodes, String nodeDelimiter) {
        super(name);

        this.nodes = new ArrayList<String>();
        if (nodes != null) {
            this.nodes.addAll(nodes);
        }
        this.nodeDelimiter = nodeDelimiter != null ? nodeDelimiter : "";
    }

    public List<String> getNodes() {
        return nodes;
    }

    public String getNodeDelimiter() {
        return nodeDelimiter;
    }

    public String getValue() {
        return StringUtils.join(nodes, nodeDelimiter);
    }

    @Override
    public void buildEnvVars(AbstractBuild<?, ?> build, EnvVars env) {
        env.put(name, getValue());
    }

    @Override
    public VariableResolver<String> createVariableResolver(AbstractBuild<?, ?> build) {
        return new VariableResolver<String>() {
            public String resolve(String name) {
                return VBoxParameterValue.this.name.equals(name) ? getValue() : null;
            }
        };
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        if (!super.equals(o)) return false;

        VBoxParameterValue that = (VBoxParameterValue) o;

        return nodeDelimiter.equals(that.nodeDelimiter) && nodes.equals(that.nodes);
    }

    @Override
    public int hashCode() {
        int result = super.hashCode();
        result = 31 * result + nodes.hashCode();
        result = 31 * result + nodeDelimiter.hashCode();
        return result;
    }
}

