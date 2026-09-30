# Third-party components

Dev Platform source is MIT licensed. Dependencies and the bundled Java runtime retain their own licenses.

- The executable Spring Boot JAR includes dependency JARs under `BOOT-INF/lib`; their `META-INF` license/notice files are retained.
- Spring Boot / Spring Framework, Apache MINA SSHD and Apache Tomcat: see their included Apache license and notice files.
- Bouncy Castle: see the included dependency license.
- Packaged builds use the JDK selected by the builder. GitHub Actions selects Eclipse Temurin 21. The linked runtime retains its `legal/` directory and available JDK notices. OpenJDK licensing is separate from this application's MIT license.
- `BUILD-INFO.json` records the selected Java version and build source. Local builds are marked as such.

Do not remove bundled notices when redistributing. This file is a component guide, not a replacement for the original licenses.
