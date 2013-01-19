package org.jenkinsci.plugins;

import hudson.Extension;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.SimpleParameterDefinition;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

public class VBoxParameterDefinition extends SimpleParameterDefinition {

    private final static Logger LOGGER = Logger.getLogger(VBoxParameterDefinition.class.getName());

    private static final long serialVersionUID = 1L;

    private String nodeDelimiter;

    @DataBoundConstructor
    public VBoxParameterDefinition(String name, String description, String nodeDelimiter) {
        super(name, description);
        this.nodeDelimiter = nodeDelimiter;
    }

    public String getNodeDelimiter() {
        return nodeDelimiter;
    }

    @Extension
    public static class DescriptorImpl extends ParameterDescriptor {
        @Override
        public String getDisplayName() {
            return "VBox node parameter";
        }
    }

    @Override
    public ParameterValue createValue(String value) {
        LOGGER.info("In VBoxParameterDefinition::createValue: " + value);

        return new VBoxParameterValue(getName(), null, getNodeDelimiter());
    }


    @Override
    public ParameterValue createValue(StaplerRequest req, JSONObject jo) {
        LOGGER.info("In VBoxParameterDefinition::createValue(2): " + jo.toString());

        List<String> nodes = new ArrayList<String>();
        Object jvalue = jo.get("nodes");
        if (jvalue instanceof String) {
            nodes.add((String) jvalue);
        } else if (jvalue instanceof JSONArray) {
            JSONArray jsonValues = (JSONArray) jvalue;
            for (int i = 0; i < jsonValues.size(); i++) {
                nodes.add(jsonValues.getString(i));
            }
        }

        VBoxParameterValue value = new VBoxParameterValue(jo.getString("name"), nodes, getNodeDelimiter());
        value.setDescription(getDescription());
        return value;
    }

    @Override
    public ParameterDefinition copyWithDefaultValue(ParameterValue defaultValueObj) {
        if (defaultValueObj instanceof VBoxParameterValue) {
            return new VBoxParameterDefinition(getName(), getDescription(), getNodeDelimiter());
        } else {
            return this;
        }
    }


}
