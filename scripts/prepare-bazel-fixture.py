#!/usr/bin/env python3
"""Build the public Bazel fixture and prepare its disposable IDE model and plugin ZIP."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "fixtures/ijpl"
BUILD = FIXTURE / "build"
BAZEL = ["bazelisk", "--output_base=" + str(BUILD / "bazel-output")]


def bazel(*arguments):
    return subprocess.run(BAZEL + list(arguments), cwd=FIXTURE, check=True, text=True, stdout=subprocess.PIPE).stdout.strip()


def main():
    BUILD.mkdir(exist_ok=True)
    if not (FIXTURE / ".local/sdk/MODULE.bazel").exists():
        raise SystemExit("First run ./gradlew :e2e:driver-plugin:exportFixtureSdk")
    try:
        bazel("build", "//:ide_model")
        execution_root = Path(bazel("info", "execution_root"))
        bazel_bin = Path(bazel("info", "bazel-bin"))
        model = json.loads((bazel_bin / "ide-model.json").read_text())
        own_jar = (bazel_bin / "fixture.jar").resolve()
        subprocess.run(["python3", str(ROOT / "scripts/verify-trace-markers.py"), str(own_jar)], check=True)
        dependencies = [(execution_root / path).resolve(strict=True) for path in model["classpath"]]
        dependencies = sorted(set(path for path in dependencies if path != own_jar))
        if not dependencies:
            raise ValueError("Bazel JavaInfo exported an empty dependency classpath")
        project = BUILD / "ide-project"
        if project.exists():
            shutil.rmtree(project)
        project.mkdir()
        for source in model["sources"]:
            relative = Path(source)
            if relative.is_absolute() or ".." in relative.parts or not source.startswith("src/main/kotlin/"):
                raise ValueError("Unexpected fixture source: " + source)
            destination = project / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(FIXTURE / relative, destination)
        module = ET.Element("module", type="JAVA_MODULE", version="4")
        facets = ET.SubElement(module, "component", name="FacetManager")
        facet = ET.SubElement(facets, "facet", type="kotlin-language", name="Kotlin")
        configuration = ET.SubElement(facet, "configuration", version="5", platform="JVM 21", allPlatforms="JVM [21]", useProjectSettings="false")
        arguments = ET.SubElement(ET.SubElement(configuration, "compilerArguments"), "stringArguments")
        for name, value in [("jvmTarget", "21"), ("languageVersion", "2.4"), ("apiVersion", "2.4")]:
            ET.SubElement(arguments, "stringArg", name=name, arg=value)
        compiler_plugins = list((FIXTURE / ".local/sdk/compose-plugin").glob("*.jar"))
        if len(compiler_plugins) != 1:
            raise ValueError("Expected the one pinned Compose compiler plugin used by Bazel")
        array_args = ET.SubElement(configuration.find("compilerArguments"), "arrayArguments")
        plugin_paths = ET.SubElement(array_args, "arrayArg", name="pluginClasspaths")
        ET.SubElement(plugin_paths, "args").text = str(compiler_plugins[0].resolve())
        manager = ET.SubElement(module, "component", name="NewModuleRootManager")
        content = ET.SubElement(manager, "content", url="file://$MODULE_DIR$")
        ET.SubElement(content, "sourceFolder", url="file://$MODULE_DIR$/src/main/kotlin", isTestSource="false")
        ET.SubElement(manager, "orderEntry", type="inheritedJdk")
        ET.SubElement(manager, "orderEntry", type="sourceFolder", forTests="false")
        entry = ET.SubElement(manager, "orderEntry", type="module-library")
        library = ET.SubElement(entry, "library", name="Bazel JavaInfo dependencies")
        classes = ET.SubElement(library, "CLASSES")
        for dependency in dependencies:
            ET.SubElement(classes, "root", url="jar://" + dependency.as_posix() + "!/")
        ET.ElementTree(module).write(project / "fixture.iml", encoding="utf-8", xml_declaration=True)
        (project / ".idea").mkdir()
        (project / ".idea/modules.xml").write_text('<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/fixture.iml" filepath="$PROJECT_DIR$/fixture.iml"/></modules></component></project>')
        (project / "model-evidence.json").write_text(json.dumps({"origin": "Bazel JavaInfo", "target": model["target"], "sourceRoot": "src/main/kotlin", "classpath": [str(path) for path in dependencies], "sourceSha256": {source: hashlib.sha256((FIXTURE / source).read_bytes()).hexdigest() for source in model["sources"]}}, indent=2))
        jar_bytes = io.BytesIO(own_jar.read_bytes())
        with zipfile.ZipFile(jar_bytes, "a") as jar:
            jar.writestr("META-INF/plugin.xml", (FIXTURE / "src/main/resources/META-INF/plugin.xml").read_bytes())
        distribution = BUILD / "distributions"
        distribution.mkdir(exist_ok=True)
        with zipfile.ZipFile(distribution / "ijpl-fixture.zip", "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("jewel-fixture/lib/fixture.jar", jar_bytes.getvalue())
            for name in ("compiler-metadata.jar", "recording.jar", "recording-compose.jar"):
                archive.write(FIXTURE / ".local/sdk/api" / name, "jewel-fixture/lib/" + name)
        print(project)
    finally:
        if (BUILD / "bazel-output/server/server.pid.txt").exists():
            bazel("shutdown")


if __name__ == "__main__":
    main()
