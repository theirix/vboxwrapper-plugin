package org.jenkinsci.plugins;

import hudson.model.Node;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;

public class VBoxUtils {
    /**
     * Finds all slave machine names
     *
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
}