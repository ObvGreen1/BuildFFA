# `libs/`

The build needs one jar that is **not** published to Maven Central and is therefore
**not committed to this repository**.

Place it in this directory before running `mvn package`:

| File | Source |
| --- | --- |
| `PlaceholderAPI-2.11.3.jar` | <https://spigotmc.org/resources/6245/> |

```bash
mkdir -p libs
curl -L -o libs/PlaceholderAPI-2.11.3.jar \
  https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.11.3/PlaceholderAPI-2.11.3.jar
```

It is wired into `pom.xml` as a `system`-scoped dependency pointing at
`${project.basedir}/libs/PlaceholderAPI-2.11.3.jar`.

`sqlite-jdbc` is **not** listed here: it is resolved from Maven Central as
`org.xerial:sqlite-jdbc` and shaded into the built jar by
`maven-shade-plugin`, so no manual download is required for it.
