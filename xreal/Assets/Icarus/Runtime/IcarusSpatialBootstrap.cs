using UnityEngine;

namespace Icarus.Spatial
{
    public sealed class IcarusSpatialBootstrap : MonoBehaviour
    {
        private void Awake()
        {
            Screen.sleepTimeout = SleepTimeout.NeverSleep;
            Application.targetFrameRate = 90;

            var cameraObject = new GameObject("ICARUS Head Camera");
            cameraObject.transform.SetParent(transform, false);
            var camera = cameraObject.AddComponent<Camera>();
            camera.clearFlags = CameraClearFlags.SolidColor;
            camera.backgroundColor = Color.black;
            camera.nearClipPlane = 0.05f;
            camera.farClipPlane = 20f;
            camera.stereoTargetEye = StereoTargetEyeMask.Both;
            cameraObject.AddComponent<AudioListener>();
            cameraObject.AddComponent<XrHeadPoseDriver>();

            var anchorObject = new GameObject("ICARUS Spatial Anchor");
            anchorObject.transform.SetParent(transform, false);
            var anchor = anchorObject.AddComponent<SpatialAnchorController>();
            anchor.Head = cameraObject.transform;

            var hud = anchorObject.AddComponent<SpatialHudView>();
            hud.Initialize(anchor, camera);

            var interactor = cameraObject.AddComponent<GazeTapInteractor>();
            interactor.HeadCamera = camera;

            var client = gameObject.AddComponent<LoopbackTelemetryClient>();
            client.SnapshotReceived += hud.ApplyTelemetry;
        }
    }
}
