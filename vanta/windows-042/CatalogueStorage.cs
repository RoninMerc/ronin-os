using System.Security.Cryptography;
using System.Text;

namespace Vanta.Core;

/// <summary>Atomic, sharded model snapshots. A large catalogue must not be one 48 MB record.</summary>
public static class CatalogueStorage
{
    public sealed class Manifest
    {
        public int Version { get; set; } = 1;
        public string Generation { get; set; } = "";
        public int Count { get; set; }
        public int Parts { get; set; }
    }
    private static string Prefix(string id) => Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(id))) + ":";
    private static string Part(string id, Manifest m, int n) => Prefix(id) + m.Generation + ":" + n;
    public static List<ModelRecord>? Read(Store store, string kind, string id, bool secret = true)
    {
        var manifest = store.Read<Manifest>(kind + "-manifest", id, secret);
        if (manifest == null) return store.Read<List<ModelRecord>>(kind, id, secret);
        if (manifest.Version != 1 || manifest.Count < 0 || manifest.Count > 1000000 || manifest.Parts != (manifest.Count + 255) / 256 || !Guid.TryParseExact(manifest.Generation, "N", out _))
            throw new VantaException("A saved catalogue manifest is invalid.", "The saved profile has not been deleted.");
        var result = new List<ModelRecord>(manifest.Count);
        for (int n = 0; n < manifest.Parts; n++)
        {
            var part = store.Read<List<ModelRecord>>(kind + "-chunk", Part(id, manifest, n), secret);
            if (part == null || part.Count != Math.Min(256, manifest.Count - n * 256)) throw new VantaException("A saved catalogue part is missing or incomplete.");
            result.AddRange(part);
        }
        return result;
    }
    public static void Write(Store store, string kind, string id, IReadOnlyCollection<ModelRecord> rows, bool secret = true)
    {
        if (rows.Count > 1000000) throw new VantaException("The catalogue exceeds the supported safety limit.");
        var next = new Manifest { Generation = Guid.NewGuid().ToString("N"), Count = rows.Count, Parts = (rows.Count + 255) / 256 };
        int n = 0;
        foreach (var part in rows.Chunk(256)) store.Save(kind + "-chunk", Part(id, next, n++), part, secret);
        // Callers serialise writes. Only this small pointer publishes the new snapshot.
        store.Save(kind + "-manifest", id, next, secret);
        foreach (var key in store.Ids(kind + "-chunk").Where(x => x.StartsWith(Prefix(id), StringComparison.Ordinal) && !x.StartsWith(Prefix(id) + next.Generation + ":", StringComparison.Ordinal)))
            store.Delete(kind + "-chunk", key);
        store.Delete(kind, id); // Remove the legacy record only after publication.
    }
    public static void Delete(Store store, string kind, string id)
    {
        store.Delete(kind + "-manifest", id); store.Delete(kind, id);
        foreach (var key in store.Ids(kind + "-chunk").Where(x => x.StartsWith(Prefix(id), StringComparison.Ordinal))) store.Delete(kind + "-chunk", key);
    }
}
