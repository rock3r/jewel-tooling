"""Small fixture-only Kotlin action with an explicit JavaInfo model.

Compiler, Compose plugin and IDE API jars come from pinned public Gradle dependencies.
No compiler inference or classpath guessing occurs in the editor plugin.
"""
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_java//java/common:java_common.bzl", "java_common")

SourceInfo = provider(fields = ["files"])

def _sdk_impl(ctx):
    infos = [JavaInfo(output_jar = jar, compile_jar = jar) for jar in ctx.files.jars]
    return [java_common.merge(infos)]

sdk_library = rule(implementation = _sdk_impl, attrs = {"jars": attr.label_list(allow_files = [".jar"])})

def _kotlin_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".jar")
    dependencies = [dep[JavaInfo] for dep in ctx.attr.deps]
    classpath = depset(transitive = [dep.transitive_compile_time_jars for dep in dependencies])
    runtime = ctx.attr._java_runtime[java_common.JavaRuntimeInfo]
    arguments = [
        "-cp", ":".join([file.path for file in ctx.files.compiler]),
        "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
        "-no-stdlib", "-no-reflect", "-jvm-target", "21",
        "-Xplugin=" + ctx.file.compose_plugin.path,
        "-classpath", ":".join([file.path for file in classpath.to_list()]),
        "-d", output.path,
    ] + [file.path for file in ctx.files.srcs]
    ctx.actions.run(
        executable = runtime.java_executable_exec_path,
        arguments = arguments,
        inputs = depset(ctx.files.srcs + ctx.files.compiler + [ctx.file.compose_plugin], transitive = [classpath, runtime.files]),
        outputs = [output],
        mnemonic = "CompileJewelFixture",
        progress_message = "Compile the public Jewel IJPL fixture",
    )
    return [DefaultInfo(files = depset([output])), JavaInfo(output_jar = output, compile_jar = output, deps = dependencies), SourceInfo(files = ctx.files.srcs)]

kotlin_library = rule(
    implementation = _kotlin_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = [".kt"]),
        "deps": attr.label_list(providers = [JavaInfo]),
        "compiler": attr.label(allow_files = [".jar"]),
        "compose_plugin": attr.label(allow_single_file = [".jar"]),
        "_java_runtime": attr.label(default = Label("@rules_java//toolchains:current_java_runtime"), cfg = "exec", providers = [java_common.JavaRuntimeInfo]),
    },
)

def _model_impl(ctx):
    target = ctx.attr.target
    output = ctx.actions.declare_file("ide-model.json")
    jars = target[JavaInfo].transitive_compile_time_jars
    ctx.actions.write(output, json.encode({
        "schemaVersion": 1,
        "target": str(target.label),
        "sources": [file.path for file in target[SourceInfo].files],
        "classpath": [file.path for file in jars.to_list()],
    }))
    return [DefaultInfo(files = depset([output], transitive = [target[DefaultInfo].files, jars]))]

export_ide_model = rule(implementation = _model_impl, attrs = {"target": attr.label(providers = [JavaInfo, SourceInfo])})
