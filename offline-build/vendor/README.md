# Bundled offline dependencies

`lib/` contains the actual JAR files used by `build.py`; no downloads occur during
builds. `SHA256SUMS` pins their bytes. `sources.json` records their origins.
`licenses/` retains notices found inside the upstream JARs. The upstream JARs
also retain their original notices. Server APIs are compile-only and excluded
from runtime plugin JARs.

PlotSquared-Core 5.13.13 was compiled locally from the archived `legacy/v5`
source at commit `93568f9f7fef4bc7264484364f5693a2800dbef8`, with the upstream
Paster source to satisfy its debug command reference. Its source and GPL license
are included in `sources/PlotSquared-v5-source.zip`. It is a compile dependency,
not a deployable replacement PlotSquared plugin. The build used the same JDK 11
compiler and bundled classpath as this project, with `worldedit-7.jar` and the
1.16.5 Spigot API; sources were passed directly to javac with Lombok enabled.

WorldEdit 6.1.9 and WorldGuard 6.2.2 supply compatible legacy compilation APIs;
Creative uses WorldEdit 7.2.0 and WorldGuard 7.0.4. No binary server APIs are
substituted with empty runtime implementations.
