using System;
using System.Collections.Generic;
using System.IO;
using UnityEditor;
using UnityEditor.Build;
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
            var projectDirectory = Directory.GetParent(Application.dataPath);
            if (projectDirectory == null)
                throw new BuildFailedException("Unable to resolve the ICARUS XREAL project root.");
            var projectRoot = projectDirectory.FullName;
            var sdkTarball = Path.Combine(projectRoot, "com.xreal.xr.tar.gz");
            if (!File.Exists(sdkTarball))
                throw new BuildFailedException("Missing com.xreal.xr.tar.gz. Download XREAL SDK 3.1.0 after accepting XREAL's terms and place the tarball at xreal/com.xreal.xr.tar.gz.");

            ValidateXrealSettings(projectRoot);
            ValidateSdkPackage(projectRoot, sdkTarball);
            EnsureInputHandling();
            EnsurePreloadedXrealAssets();
            EditorUserBuildSettings.SwitchActiveBuildTarget(BuildTargetGroup.Android, BuildTarget.Android);
            EditorUserBuildSettings.buildAppBundle = Environment.GetEnvironmentVariable("ICARUS_XREAL_AAB") == "1";

            PlayerSettings.companyName = "ICARUS";
            PlayerSettings.productName = "ICARUS Spatial HUD";
            PlayerSettings.SetApplicationIdentifier(BuildTargetGroup.Android, "com.icarusalmighty.spatial");
            PlayerSettings.bundleVersion = Environment.GetEnvironmentVariable("ICARUS_XREAL_VERSION") ?? "1.0.0";
            int versionCode;
            PlayerSettings.Android.bundleVersionCode = int.TryParse(Environment.GetEnvironmentVariable("ICARUS_XREAL_VERSION_CODE"), out versionCode) ? versionCode : 1;
            PlayerSettings.defaultInterfaceOrientation = UIOrientation.LandscapeLeft;
            PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel29;
            PlayerSettings.Android.targetSdkVersion = AndroidSdkVersions.AndroidApiLevelAuto;
            PlayerSettings.SetScriptingBackend(BuildTargetGroup.Android, ScriptingImplementation.IL2CPP);
            PlayerSettings.Android.targetArchitectures = AndroidArchitecture.ARM64;
            PlayerSettings.SetGraphicsAPIs(BuildTarget.Android, new[] { GraphicsDeviceType.OpenGLES3 });
            QualitySettings.vSyncCount = 0;

            Directory.CreateDirectory("Assets/Generated");
            var scene = EditorSceneManager.NewScene(NewSceneSetup.EmptyScene, NewSceneMode.Single);
            new GameObject("ICARUS Spatial Runtime").AddComponent<Icarus.Spatial.IcarusSpatialBootstrap>();
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
                throw new BuildFailedException("ICARUS XREAL build failed: " + report.summary.result);
            Debug.Log("ICARUS XREAL build complete: " + output);
        }

        private static void EnsureInputHandling()
        {
            var objects = AssetDatabase.LoadAllAssetsAtPath("ProjectSettings/ProjectSettings.asset");
            if (objects == null || objects.Length == 0)
                throw new BuildFailedException("Unity PlayerSettings asset is unavailable.");
            var serialized = new SerializedObject(objects[0]);
            var property = serialized.FindProperty("activeInputHandler");
            if (property == null)
                throw new BuildFailedException("Unity activeInputHandler setting is unavailable.");
            property.intValue = 2; // Both: XREAL template baseline, with new Input System enabled.
            serialized.ApplyModifiedPropertiesWithoutUndo();
        }

        private static void EnsurePreloadedXrealAssets()
        {
            var paths = new[]
            {
                "Assets/XR/Settings/XREALSettings.asset",
                "Assets/XR/XRGeneralSettingsPerBuildTarget.asset"
            };
            var assets = new List<UnityEngine.Object>(PlayerSettings.GetPreloadedAssets());
            foreach (var path in paths)
            {
                var asset = AssetDatabase.LoadAssetAtPath<UnityEngine.Object>(path);
                if (asset == null)
                    throw new BuildFailedException("Required XREAL preload asset is unavailable: " + path);
                if (!assets.Contains(asset)) assets.Add(asset);
            }
            PlayerSettings.SetPreloadedAssets(assets.ToArray());
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

        private static void ValidateSdkPackage(string projectRoot, string sdkTarball)
        {
            var manifest = Path.Combine(projectRoot, "Packages", "manifest.json");
            var manifestText = File.ReadAllText(manifest);
            if (!manifestText.Contains("\"com.xreal.xr\": \"file:../com.xreal.xr.tar.gz\""))
                throw new BuildFailedException("Unity Package Manager must reference the local accepted XREAL SDK archive.");
            var fileInfo = new FileInfo(sdkTarball);
            if (fileInfo.Length < 10 * 1024 * 1024)
                throw new BuildFailedException("XREAL SDK archive is unexpectedly small or incomplete.");
        }
    }
}
