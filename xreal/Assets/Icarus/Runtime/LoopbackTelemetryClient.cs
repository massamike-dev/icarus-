using System;
using System.Collections.Concurrent;
using System.IO;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Newtonsoft.Json.Linq;
using UnityEngine;

namespace Icarus.Spatial
{
    public sealed class LoopbackTelemetryClient : MonoBehaviour
    {
        public event Action<TelemetrySnapshot> SnapshotReceived;
        private readonly ConcurrentQueue<TelemetrySnapshot> pending = new ConcurrentQueue<TelemetrySnapshot>();
        private CancellationTokenSource cancellation;
        private SpatialLaunchContext launch;

        public bool HasLaunchContext { get; private set; }

        private void Start()
        {
            HasLaunchContext = SpatialLaunchContext.TryRead(out launch);
            if (!HasLaunchContext)
            {
                pending.Enqueue(new TelemetrySnapshot { Connected = false, Error = "ICARUS SPATIAL BRIDGE NOT ACTIVE" });
                return;
            }
            cancellation = new CancellationTokenSource();
            _ = PollLoop(cancellation.Token);
        }

        private void Update()
        {
            TelemetrySnapshot snapshot;
            while (pending.TryDequeue(out snapshot))
            {
                var handler = SnapshotReceived;
                if (handler != null) handler(snapshot);
            }
        }

        private void OnDestroy()
        {
            if (cancellation != null)
            {
                cancellation.Cancel();
                cancellation.Dispose();
                cancellation = null;
            }
        }

        private async Task PollLoop(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    var body = await RequestTelemetry(token);
                    pending.Enqueue(Parse(body));
                    await Task.Delay(500, token);
                }
                catch (OperationCanceledException) { break; }
                catch (Exception e)
                {
                    pending.Enqueue(new TelemetrySnapshot { Connected = false, Error = e.Message });
                    try { await Task.Delay(1200, token); } catch (OperationCanceledException) { break; }
                }
            }
        }

        private async Task<string> RequestTelemetry(CancellationToken token)
        {
            using (var client = new TcpClient())
            using (token.Register(() => client.Dispose()))
            {
                await client.ConnectAsync("127.0.0.1", launch.Port);
                using (var stream = client.GetStream())
                {
                    var request = "GET /telemetry HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + launch.Token + "\r\nConnection: close\r\n\r\n";
                    var bytes = Encoding.ASCII.GetBytes(request);
                    await stream.WriteAsync(bytes, 0, bytes.Length, token);
                    await stream.FlushAsync(token);
                    using (var reader = new StreamReader(stream, Encoding.UTF8))
                    {
                        var response = await reader.ReadToEndAsync();
                        var split = response.IndexOf("\r\n\r\n", StringComparison.Ordinal);
                        if (split < 0) throw new IOException("Malformed ICARUS spatial bridge response");
                        if (!response.StartsWith("HTTP/1.1 200", StringComparison.Ordinal))
                            throw new IOException("ICARUS spatial bridge rejected the request");
                        return response.Substring(split + 4);
                    }
                }
            }
        }

        private static TelemetrySnapshot Parse(string json)
        {
            var root = JObject.Parse(json);
            var t = root["telemetry"] as JObject;
            return new TelemetrySnapshot
            {
                Connected = root.Value<bool?>("connected") == true,
                SampleTimeMs = root.Value<long?>("sampleTimeMs") ?? 0L,
                Error = root.Value<string>("error"),
                SpeedMph = Number(t, "speedMph"),
                Rpm = Number(t, "rpm"),
                CoolantF = Number(t, "coolantF"),
                FuelPercent = Number(t, "fuelPercent"),
                EngineLoadPercent = Number(t, "engineLoadPercent"),
                ThrottlePercent = Number(t, "throttlePercent"),
                Voltage = Number(t, "voltage")
            };
        }

        private static double? Number(JObject obj, string name)
        {
            if (obj == null) return null;
            var token = obj[name];
            if (token == null || token.Type == JTokenType.Null) return null;
            return token.Value<double?>();
        }
    }
}
