using System;
using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.Rendering;

namespace Icarus.Spatial.Editor
{
    public static class IcarusXrealBuild
    {
        [MenuItem("ICARUS/Build XREAL Android")]
        public static void BuildAndroid()
        {
            var projectRoot = Directory.GetParent(Application.dataPath)!.FullName;
            var sdkTarball = Path.Combine(projectRoot, "com.xreal.xr.tar.gz");
            if (!File.Exists(sdkTarball))
                throw new BuildFailedException("Missing com.xreal.xr.tar.gz. Download XREAL SDK 3.1.0 after accepting XREAL's terms and place the tarball at xreal/com.xreal.xr.tar.gz.");

            ValidateXrealSettings(projectRoot);
            EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
            EditorUserBuildSettings.buildAppBundle = Environment.GetEnvironmentVariable("ICARUS_XREAL_AAB") == "1";

            PlayerSettings.companyName = "ICARUS";
            PlayerSettings.productName = "ICARUS Spatial HUD";
            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, "com.icarusalmighty.spatial");
            PlayerSettings.bundleVersion = Environment.GetEnvironmentVariable("ICARUS_XREAL_VERSION") ?? "1.0.0";
            PlayerSettings.Android.bundleVersionCode = int.TryParse(Environment.GetEnvironmentVariable("ICARUS_XREAL_VERSION_CODE"), out var code) ? code : 1;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.Portrait;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { GraphicsDeviceType.OpenGLES3 });
            QualitySettings.vSyncCount = 0;

            Directory.CreateDirectory("Assets/Generated");
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            new GameObject("ICARUS Spatial Runtime").AddComponent<IcarusSpatialBootstrap>();
            const string scenePath = "Assets/Generated/IcarusSpatial.unity";
            EditorSceneManager.SaveScene(scene, scenePath);

            Directory.CreateDirectory(Path.Combine(projectRoot, "Build"));
            var defaultName = EditorUserBuildSettings.buildAppBundle ? "ICARUS-XREAL.aab" : "ICARUS-XREAL.apk";
            var output = Environment.GetEnvironmentVariable("ICARUS_XREAL_OUTPUT") ?? Path.Combine(projectRoot, "Build", defaultName);
            var options = new BuildPlayerOptions
            {
                scenes = new[] { scenePath },
                locationPathName = output,
                target = BuildTarget.Android,
                targetGroup = BuildTargetGroup.Android,
                options = BuildOptions.None
            };
            var report = BuildPipeline.BuildPlayer(options);
            if (report.summary.result != BuildResult.Succeeded)
                throw new BuildFailedException($"ICARUS XREAL build failed: {report.summary.result}");
            Debug.Log($"ICARUS XREAL build complete: {output}");
        }

        private static void ValidateXrealSettings(string projectRoot)
        {
            var settingsPath = Path.Combine(projectRoot, "Assets", "XR", "Settings", "XREALSettings.asset");
            var text = File.ReadAllText(settingsPath);
            if (!text.Contains("InitialTrackingType: 1"))
                throw new BuildFailedException("ICARUS Air 2 Pro build must use MODE_3DOF (1).");
            if (!text.Contains("StereoRendering: 2"))
                throw new BuildFailedException("ICARUS XREAL build must use single-pass stereo.");
            if (!text.Contains("SupportMultiResume: 1"))
                throw new BuildFailedException("ICARUS XREAL build requires multi-resume for Beam Pro.");
        }
    }
}
