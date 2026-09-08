using System;
using UnityEngine;

namespace Icarus.Spatial
{
    public readonly struct SpatialLaunchContext
    {
        public readonly int Port;
        public readonly string Token;

        public SpatialLaunchContext(int port, string token)
        {
            Port = port;
            Token = token;
        }

        public static bool TryRead(out SpatialLaunchContext context)
        {
#if UNITY_ANDROID && !UNITY_EDITOR
            try
            {
                using var unityPlayer = new AndroidJavaClass("com.unity3d.player.UnityPlayer");
                using var activity = unityPlayer.GetStatic<AndroidJavaObject>("currentActivity");
                using var intent = activity.Call<AndroidJavaObject>("getIntent");
                using var data = intent?.Call<AndroidJavaObject>("getData");
                if (data != null)
                {
                    var portText = data.Call<string>("getQueryParameter", "port");
                    var token = data.Call<string>("getQueryParameter", "token");
                    if (int.TryParse(portText, out var port) && port > 0 && !string.IsNullOrWhiteSpace(token))
                    {
                        context = new SpatialLaunchContext(port, token);
                        return true;
                    }
                }
            }
            catch (Exception e)
            {
                Debug.LogError($"ICARUS launch context failed: {e.Message}");
            }
#endif
            var editorToken = Environment.GetEnvironmentVariable("ICARUS_SPATIAL_TOKEN");
            if (int.TryParse(Environment.GetEnvironmentVariable("ICARUS_SPATIAL_PORT"), out var editorPort) &&
                editorPort > 0 && !string.IsNullOrWhiteSpace(editorToken))
            {
                context = new SpatialLaunchContext(editorPort, editorToken);
                return true;
            }
            context = default;
            return false;
        }
    }
}
