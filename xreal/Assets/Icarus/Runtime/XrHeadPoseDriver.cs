using UnityEngine;
using UnityEngine.XR;

namespace Icarus.Spatial
{
    public sealed class XrHeadPoseDriver : MonoBehaviour
    {
        private InputDevice head;

        private void OnEnable() => RefreshDevice();

        private void Update()
        {
            if (!head.isValid) RefreshDevice();
            if (head.TryGetFeatureValue(CommonUsages.deviceRotation, out Quaternion rotation))
                transform.localRotation = rotation;
            if (head.TryGetFeatureValue(CommonUsages.devicePosition, out Vector3 position))
                transform.localPosition = position;
        }

        private void RefreshDevice() => head = InputDevices.GetDeviceAtXRNode(XRNode.Head);
    }
}
