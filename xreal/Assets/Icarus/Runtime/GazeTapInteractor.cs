using UnityEngine;

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
            if (Physics.Raycast(ray, out var hit, MaxDistance))
                hit.collider.GetComponentInParent<HudActionTarget>()?.Invoke();
        }

        private static bool PressedThisFrame()
        {
            if (Input.GetMouseButtonDown(0) || Input.GetKeyDown(KeyCode.Return) || Input.GetKeyDown(KeyCode.Space)) return true;
            return Input.touchCount > 0 && Input.GetTouch(0).phase == TouchPhase.Began;
        }
    }
}
