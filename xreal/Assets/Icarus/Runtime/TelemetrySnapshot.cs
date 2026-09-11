namespace Icarus.Spatial
{
    public sealed class TelemetrySnapshot
    {
        public bool Connected { get; set; }
        public long SampleTimeMs { get; set; }
        public string Error { get; set; }
        public double? SpeedMph { get; set; }
        public double? Rpm { get; set; }
        public double? CoolantF { get; set; }
        public double? FuelPercent { get; set; }
        public double? EngineLoadPercent { get; set; }
        public double? ThrottlePercent { get; set; }
        public double? Voltage { get; set; }
    }
}
