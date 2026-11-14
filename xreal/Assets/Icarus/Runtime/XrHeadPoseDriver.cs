using UnityEngine;
using UnityEngine.XR;

namespace Icarus.Spatial
{
    public sealed class XrHeadPoseDriver : MonoBehaviour
    {
        private InputDevice head;
        public bool HasTracking { get; private set; }

        private void OnEnable() => RefreshDevice();

        private void Update()
        {
            if (!head.isValid) RefreshDevice();
            HasTracking = head.isValid &&
                head.TryGetFeatureValue(CommonUsages.isTracked, out bool tracked) && tracked &&
                head.TryGetFeatureValue(CommonUsages.trackingState, out InputTrackingState state) &&
                (state & InputTrackingState.Rotation) != 0;
            if (!HasTracking) return;
            if (head.TryGetFeatureValue(CommonUsages.deviceRotation, out Quaternion rotation) &&
                Finite(rotation.x) && Finite(rotation.y) &&
                Finite(rotation.z) && Finite(rotation.w) &&
                Quaternion.Dot(rotation, rotation) > 0.5f)
                transform.localRotation = rotation.normalized;
            else { HasTracking = false; return; }
            if (head.TryGetFeatureValue(CommonUsages.devicePosition, out Vector3 position))
                transform.localPosition = position;
        }

        private static bool Finite(float value) => !float.IsNaN(value) && !float.IsInfinity(value);

        private void OnDisable() => HasTracking = false;

        private void RefreshDevice() => head = InputDevices.GetDeviceAtXRNode(XRNode.Head);
    }
}
