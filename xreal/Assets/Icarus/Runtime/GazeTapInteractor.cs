using UnityEngine;
using UnityEngine.InputSystem;

namespace Icarus.Spatial
{
    public sealed class GazeTapInteractor : MonoBehaviour
    {
        public Camera HeadCamera { get; set; }
        public float MaxDistance = 5f;

        private void Update()
        {
            if (HeadCamera == null || !PressedThisFrame()) return;
            var ray = new Ray(HeadCamera.transform.position, HeadCamera.transform.forward);
            RaycastHit hit;
            if (Physics.Raycast(ray, out hit, MaxDistance))
            {
                var target = hit.collider.GetComponentInParent<HudActionTarget>();
                if (target != null) target.Invoke();
            }
        }

        private static bool PressedThisFrame()
        {
            if (Mouse.current != null && Mouse.current.leftButton.wasPressedThisFrame) return true;
            if (Touchscreen.current != null && Touchscreen.current.primaryTouch.press.wasPressedThisFrame) return true;
            if (Keyboard.current != null &&
                (Keyboard.current.enterKey.wasPressedThisFrame || Keyboard.current.spaceKey.wasPressedThisFrame)) return true;
            return false;
        }
    }
}
