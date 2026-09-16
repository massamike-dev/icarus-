using System;
using UnityEngine;

namespace Icarus.Spatial
{
    public enum HudAction { ToggleFollowMode, ToggleDiagnostics, Recenter }

    public sealed class HudActionTarget : MonoBehaviour
    {
        public HudAction Action;
        public Action<HudAction> Handler;
        public void Invoke() => Handler?.Invoke(Action);
    }
}
