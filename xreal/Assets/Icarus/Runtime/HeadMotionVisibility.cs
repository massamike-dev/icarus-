using UnityEngine;

namespace Icarus.Spatial
{
    // Stationary prototype thresholds; not validated for use by a driver.
    [DefaultExecutionOrder(100)]
    public sealed class HeadMotionVisibility : MonoBehaviour
    {
        private XrHeadPoseDriver pose;
        private SpatialHudView hud;
        private SpatialAnchorController anchor;
        private Quaternion forward, previous;
        private bool calibrated, sampled, suppressed = true;
        private float stableTime, alpha;
        private bool paused, focused = true;

        public void Initialize(XrHeadPoseDriver source, SpatialHudView view, SpatialAnchorController controller)
        {
            pose = source; hud = view; anchor = controller;
            anchor.Recentered += Calibrate;
            hud.SetVisibility(0f);
        }

        private void Calibrate()
        {
            if (pose == null || !pose.HasTracking) return;
            forward = pose.transform.rotation;
            calibrated = true;
            Hide();
        }

        private void Hide()
        {
            sampled = false; suppressed = true; stableTime = 0f; alpha = 0f;
            if (hud != null) hud.SetVisibility(0f);
        }

        private void LateUpdate()
        {
            float dt = Time.unscaledDeltaTime;
            if (paused || !focused || pose == null || hud == null || !pose.HasTracking || dt <= 0f || dt > 0.1f)
            { Hide(); return; }
            if (!calibrated) { anchor.Recenter(); Calibrate(); }
            Quaternion current = pose.transform.rotation;
            if (!sampled) { previous = current; sampled = true; return; }
            float speed = Quaternion.Angle(previous, current) / dt;
            float angle = Quaternion.Angle(forward, current);
            previous = current;

            // Hysteresis: hide at 12 degrees / 45 deg/s, recover inside 7 degrees
            // and below 12 deg/s continuously for half a second.
            if (angle >= 12f || speed >= 45f)
            { suppressed = true; stableTime = 0f; }
            if (suppressed)
            {
                stableTime = angle <= 7f && speed <= 12f ? stableTime + dt : 0f;
                if (stableTime >= 0.5f) suppressed = false;
            }
            alpha = Mathf.MoveTowards(alpha, suppressed ? 0f : 1f, dt / (suppressed ? 0.08f : 0.3f));
            hud.SetVisibility(alpha);
        }

        private void OnApplicationPause(bool value) { paused = value; if (value) Hide(); }
        private void OnApplicationFocus(bool value) { focused = value; if (!value) Hide(); }
        private void OnDisable() => Hide();
        private void OnDestroy() { if (anchor != null) anchor.Recentered -= Calibrate; }
    }
}
