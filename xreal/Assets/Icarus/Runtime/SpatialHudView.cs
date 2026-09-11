using System;
using System.Collections.Generic;
using UnityEngine;
using UnityEngine.UI;

namespace Icarus.Spatial
{
    public sealed class SpatialHudView : MonoBehaviour
    {
        private readonly Dictionary<string, Text> values = new();
        private SpatialAnchorController anchor;
        private GameObject diagnosticsPanel;
        private Text modeText;
        private Text sourceText;
        private Material volumetricMaterial;
        private bool diagnosticsExpanded = true;

        private static readonly Color Navy = new(0.01f, 0.03f, 0.08f, 0.80f);
        private static readonly Color Cyan = new(0.21f, 0.86f, 1f, 1f);
        private static readonly Color Gold = new(0.86f, 0.69f, 0.30f, 1f);
        private static readonly Color White = new(0.92f, 0.97f, 1f, 1f);
        private static readonly Color Dim = new(0.47f, 0.68f, 0.76f, 1f);

        public void Initialize(SpatialAnchorController anchorController, Camera headCamera)
        {
            anchor = anchorController;
            var canvasObject = new GameObject("ICARUS World Canvas", typeof(RectTransform), typeof(Canvas), typeof(CanvasScaler));
            canvasObject.transform.SetParent(transform, false);
            var canvas = canvasObject.GetComponent<Canvas>();
            canvas.renderMode = RenderMode.WorldSpace;
            canvas.worldCamera = headCamera;
            var rect = canvasObject.GetComponent<RectTransform>();
            rect.sizeDelta = new Vector2(1600f, 900f);
            rect.localScale = Vector3.one * 0.00115f;

            CreateText(rect, "ICARUS", new Vector2(-720f, 370f), new Vector2(330f, 70f), 50, Gold, TextAnchor.MiddleLeft, true);
            CreateText(rect, "SPATIAL HUD • XREAL", new Vector2(-720f, 325f), new Vector2(420f, 45f), 22, Cyan, TextAnchor.MiddleLeft, false);
            sourceText = CreateText(rect, "NO LIVE VEHICLE DATA", new Vector2(-720f, 285f), new Vector2(500f, 42f), 20, Dim, TextAnchor.MiddleLeft, false);

            var speedPanel = CreatePanel(rect, "Speed", new Vector2(-590f, 20f), new Vector2(350f, 390f));
            CreateText(speedPanel, "SPEED", new Vector2(0f, 125f), new Vector2(280f, 45f), 20, Dim, TextAnchor.MiddleCenter, false);
            values["speed"] = CreateText(speedPanel, "—", new Vector2(0f, 15f), new Vector2(300f, 150f), 105, White, TextAnchor.MiddleCenter, true);
            CreateText(speedPanel, "MPH", new Vector2(0f, -85f), new Vector2(220f, 40f), 24, Cyan, TextAnchor.MiddleCenter, false);
            values["rpm"] = CreateText(speedPanel, "— RPM", new Vector2(0f, -135f), new Vector2(260f, 45f), 24, Gold, TextAnchor.MiddleCenter, false);

            var navPanel = CreatePanel(rect, "Navigation", new Vector2(0f, 250f), new Vector2(520f, 190f));
            CreateText(navPanel, "NAVIGATION", new Vector2(-210f, 55f), new Vector2(220f, 35f), 18, Dim, TextAnchor.MiddleLeft, false);
            CreateText(navPanel, "NOT ACTIVE", new Vector2(-210f, 0f), new Vector2(300f, 55f), 38, White, TextAnchor.MiddleLeft, true);
            CreateText(navPanel, "LIVE ROUTE SOURCE REQUIRED", new Vector2(-210f, -55f), new Vector2(430f, 35f), 17, Cyan, TextAnchor.MiddleLeft, false);

            diagnosticsPanel = CreatePanel(rect, "Diagnostics", new Vector2(575f, 30f), new Vector2(390f, 430f)).gameObject;
            var diagRect = diagnosticsPanel.GetComponent<RectTransform>();
            CreateText(diagRect, "ENGINE / OBD", new Vector2(-150f, 165f), new Vector2(300f, 38f), 19, Dim, TextAnchor.MiddleLeft, false);
            values["temp"] = Metric(diagRect, "COOLANT", 100f);
            values["load"] = Metric(diagRect, "LOAD", 35f);
            values["fuel"] = Metric(diagRect, "FUEL", -30f);
            values["voltage"] = Metric(diagRect, "VOLTAGE", -95f);

            var modePanel = CreatePanel(rect, "Mode", new Vector2(615f, 330f), new Vector2(310f, 90f));
            modeText = CreateText(modePanel, "BODY ANCHOR", Vector2.zero, new Vector2(260f, 50f), 21, Gold, TextAnchor.MiddleCenter, true);
            AddAction(modePanel.gameObject, HudAction.ToggleFollowMode, new Vector3(310f, 90f, 20f));

            var centerPanel = CreatePanel(rect, "Vehicle Scan", new Vector2(0f, -90f), new Vector2(520f, 430f));
            CreateText(centerPanel, "OBD VEHICLE SCAN", new Vector2(0f, -165f), new Vector2(330f, 36f), 18, Cyan, TextAnchor.MiddleCenter, true);
            CreateText(centerPanel, "GAZE + TAP TO EXPAND", new Vector2(0f, -205f), new Vector2(350f, 32f), 14, Dim, TextAnchor.MiddleCenter, false);
            AddAction(centerPanel.gameObject, HudAction.ToggleDiagnostics, new Vector3(520f, 430f, 20f));
            CreateVehicleWireframe();
            CreateVolumetricLayer();

            var recenterPanel = CreatePanel(rect, "Recenter", new Vector2(-10f, -380f), new Vector2(270f, 70f));
            CreateText(recenterPanel, "RECENTER", Vector2.zero, new Vector2(220f, 40f), 18, White, TextAnchor.MiddleCenter, true);
            AddAction(recenterPanel.gameObject, HudAction.Recenter, new Vector3(270f, 70f, 20f));

            CreateText(rect, "LIVE DATA ONLY • NO SIMULATED TELEMETRY", new Vector2(-720f, -410f), new Vector2(720f, 35f), 16, Gold, TextAnchor.MiddleLeft, false);
        }

        public void ApplyTelemetry(TelemetrySnapshot snapshot)
        {
            sourceText.text = snapshot.Connected ? "OBD LIVE • BEAM PRO" : (snapshot.Error ?? "NO LIVE VEHICLE DATA").ToUpperInvariant();
            sourceText.color = snapshot.Connected ? Cyan : Gold;
            values["speed"].text = Format(snapshot.SpeedMph, 0);
            values["rpm"].text = $"{Format(snapshot.Rpm, 0)} RPM";
            values["temp"].text = snapshot.CoolantF.HasValue ? $"{snapshot.CoolantF.Value:0}°F" : "—";
            values["load"].text = snapshot.EngineLoadPercent.HasValue ? $"{snapshot.EngineLoadPercent.Value:0}%" : "—";
            values["fuel"].text = snapshot.FuelPercent.HasValue ? $"{snapshot.FuelPercent.Value:0}%" : "—";
            values["voltage"].text = snapshot.Voltage.HasValue ? $"{snapshot.Voltage.Value:0.0} V" : "—";

            if (volumetricMaterial != null)
            {
                var heat = snapshot.CoolantF.HasValue ? Mathf.InverseLerp(195f, 245f, (float)snapshot.CoolantF.Value) : 0f;
                var pulse = snapshot.Rpm.HasValue ? Mathf.Clamp01((float)snapshot.Rpm.Value / 5000f) : 0f;
                volumetricMaterial.SetFloat("_Heat", heat);
                volumetricMaterial.SetFloat("_Pulse", pulse);
            }
        }

        private Text Metric(RectTransform parent, string label, float y)
        {
            CreateText(parent, label, new Vector2(-150f, y), new Vector2(150f, 38f), 18, Dim, TextAnchor.MiddleLeft, false);
            return CreateText(parent, "—", new Vector2(70f, y), new Vector2(190f, 42f), 29, White, TextAnchor.MiddleRight, true);
        }

        private RectTransform CreatePanel(RectTransform parent, string name, Vector2 position, Vector2 size)
        {
            var go = new GameObject(name, typeof(RectTransform), typeof(Image));
            go.transform.SetParent(parent, false);
            var rt = go.GetComponent<RectTransform>();
            rt.anchoredPosition = position;
            rt.sizeDelta = size;
            var image = go.GetComponent<Image>();
            image.color = Navy;
            return rt;
        }

        private Text CreateText(RectTransform parent, string text, Vector2 position, Vector2 size, int fontSize, Color color, TextAnchor align, bool bold)
        {
            var go = new GameObject($"Text {text}", typeof(RectTransform), typeof(Text));
            go.transform.SetParent(parent, false);
            var rt = go.GetComponent<RectTransform>();
            rt.anchoredPosition = position;
            rt.sizeDelta = size;
            var t = go.GetComponent<Text>();
            t.text = text;
            t.font = Resources.GetBuiltinResource<Font>("Arial.ttf");
            t.fontSize = fontSize;
            t.fontStyle = bold ? FontStyle.Bold : FontStyle.Normal;
            t.alignment = align;
            t.color = color;
            t.horizontalOverflow = HorizontalWrapMode.Overflow;
            t.verticalOverflow = VerticalWrapMode.Overflow;
            return t;
        }

        private void AddAction(GameObject go, HudAction action, Vector3 colliderSize)
        {
            var target = go.AddComponent<HudActionTarget>();
            target.Action = action;
            target.Handler = HandleAction;
            var collider = go.AddComponent<BoxCollider>();
            collider.size = colliderSize;
        }

        private void HandleAction(HudAction action)
        {
            switch (action)
            {
                case HudAction.ToggleFollowMode:
                    anchor.ToggleMode();
                    modeText.text = anchor.Mode == SpatialFollowMode.BodyAnchor ? "BODY ANCHOR" : "SMOOTH FOLLOW";
                    break;
                case HudAction.ToggleDiagnostics:
                    diagnosticsExpanded = !diagnosticsExpanded;
                    diagnosticsPanel.SetActive(diagnosticsExpanded);
                    break;
                case HudAction.Recenter:
                    anchor.Recenter();
                    break;
            }
        }

        private void CreateVehicleWireframe()
        {
            var go = new GameObject("Vehicle Hologram");
            go.transform.SetParent(transform, false);
            go.transform.localPosition = new Vector3(0f, -0.08f, 0.03f);
            var line = go.AddComponent<LineRenderer>();
            line.useWorldSpace = false;
            line.loop = true;
            line.widthMultiplier = 0.008f;
            line.positionCount = 8;
            line.SetPositions(new[]
            {
                new Vector3(-0.48f, -0.12f, 0f), new Vector3(-0.32f, 0.12f, 0f),
                new Vector3(-0.05f, 0.22f, 0f), new Vector3(0.28f, 0.20f, 0f),
                new Vector3(0.48f, 0.04f, 0f), new Vector3(0.44f, -0.16f, 0f),
                new Vector3(0.12f, -0.22f, 0f), new Vector3(-0.34f, -0.20f, 0f)
            });
            var mat = new Material(Shader.Find("Sprites/Default"));
            mat.color = Cyan;
            line.material = mat;
        }

        private void CreateVolumetricLayer()
        {
            var quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
            quad.name = "Volumetric Scan Field";
            quad.transform.SetParent(transform, false);
            quad.transform.localPosition = new Vector3(0f, -0.07f, 0.08f);
            quad.transform.localScale = new Vector3(1.05f, 0.68f, 1f);
            Destroy(quad.GetComponent<Collider>());
            var shader = Shader.Find("ICARUS/VolumetricHUD");
            if (shader != null)
            {
                volumetricMaterial = new Material(shader);
                quad.GetComponent<MeshRenderer>().material = volumetricMaterial;
            }
        }

        private static string Format(double? value, int decimals)
        {
            if (!value.HasValue) return "—";
            return decimals == 0 ? value.Value.ToString("0") : value.Value.ToString("0.0");
        }
    }
}
