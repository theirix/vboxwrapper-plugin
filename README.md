VBoxWrapper plugin for Jenkins
==============================

VirtualBox start/stop wrapper for [Jenkins](http://jenkins-ci.org)

Plugin provides a build wrapper for starting and stopping slaves on the virtual machine.
Start/stop is performed by launching shell scripts (init.d, VBoxManage for example).

Similar VirtualBox Plugin requires web service so this plugin may be lighter.

Building
--------

Add a profile to maven `~/.m2/settings.xml`:

```xml
    <profile>
      <id>jenkins</id>
      <activation>
        <activeByDefault>true</activeByDefault> <!-- change this to false, if you don't like to have it on per default -->
      </activation>
      <repositories>
        <repository>
          <id>repo.jenkins-ci.org</id>
          <url>http://repo.jenkins-ci.org/public/</url>
        </repository>
      </repositories>
      <pluginRepositories>
        <pluginRepository>
          <id>repo.jenkins-ci.org</id>
          <url>http://repo.jenkins-ci.org/public/</url>
        </pluginRepository>
      </pluginRepositories>
    </profile>

```

Launch a build:

    mvn clean compile package

Get a plugin

    target/vboxwrapper.hpi

Install to a Jenkins instance by the file upload.

License information
-------------------

You can use any code from this project under the terms of [BSD License](http://opensource.org/licenses/bsd-license.php)

