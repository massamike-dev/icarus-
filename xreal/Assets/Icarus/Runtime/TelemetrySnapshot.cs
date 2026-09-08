namespace Icarus.Spatial
{
    public sealed class TelemetrySnapshot
    {
        public bool Connected { get; init; }
        public long SampleTimeMs { get; init; }
        public string Error { get; init; }
        public double? SpeedMph { get; init; }
        public double? Rpm { get; init; }
        public double? CoolantF { get; init; }
        public double? FuelPercent { get; init; }
        public double? EngineLoadPercent { get; init; }
        public double? ThrottlePercent { get; init; }
        public double? Voltage { get; init; }
    }
}
