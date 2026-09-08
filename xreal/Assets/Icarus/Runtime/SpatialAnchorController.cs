using UnityEngine;

namespace Icarus.Spatial
{
    public enum SpatialFollowMode { BodyAnchor, SmoothFollow }

    public sealed class SpatialAnchorController : MonoBehaviour
    {
        public Transform Head { get; set; }
        public SpatialFollowMode Mode { get; private set; } = SpatialFollowMode.BodyAnchor;
        public Vector3 Offset = new Vector3(0f, -0.12f, 2.2f);
        public float BodyCatchupDegrees = 22f;
        public float BodyCatchupSpeed = 3.5f;
        public float SmoothFollowSpeed = 5.5f;

        private float anchorYaw;
        private bool initialized;

        private void LateUpdate()
        {
            if (Head == null) return;
            var headYaw = Head.eulerAngles.y;
            if (!initialized)
            {
                anchorYaw = headYaw;
                initialized = true;
            }

            var delta = Mathf.DeltaAngle(anchorYaw, headYaw);
            if (Mode == SpatialFollowMode.SmoothFollow || Mathf.Abs(delta) >= BodyCatchupDegrees)
            {
                var speed = Mode == SpatialFollowMode.SmoothFollow ? SmoothFollowSpeed : BodyCatchupSpeed;
                anchorYaw = Mathf.LerpAngle(anchorYaw, headYaw, 1f - Mathf.Exp(-speed * Time.unscaledDeltaTime));
            }

            var yaw = Quaternion.Euler(0f, anchorYaw, 0f);
            transform.SetPositionAndRotation(Head.position + yaw * Offset, yaw);
        }

        public void ToggleMode()
        {
            Mode = Mode == SpatialFollowMode.BodyAnchor ? SpatialFollowMode.SmoothFollow : SpatialFollowMode.BodyAnchor;
            Recenter();
        }

        public void Recenter()
        {
            if (Head == null) return;
            anchorYaw = Head.eulerAngles.y;
            initialized = true;
        }
    }
}
