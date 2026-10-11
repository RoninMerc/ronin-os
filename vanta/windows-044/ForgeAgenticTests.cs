using System.Threading.Tasks;
using System.Linq;
using Vanta.Core;

namespace Vanta.Tests;

public static partial class Program
{
    static async Task ForgeAgenticTests()
    {
        await Test("Agentic repair targets compiler source only", () =>
        {
            var p = new ProjectRecord
            {
                Platform = "Android",
                Files = new()
                {
                    new() { Path = "app/src/main/java/qa/A.kt", Content = "package qa\nclass A" },
                    new() { Path = "app/src/main/java/qa/B.kt", Content = "package qa\nclass B" },
                    new() { Path = "app/src/main/java/qa/C.kt", Content = "package qa\nclass C" },
                    new() { Path = "app/src/main/java/qa/D.kt", Content = "package qa\nclass D" },
                    new() { Path = "app/src/test/java/qa/ATest.kt", Content = "package qa\nclass ATest" }
                }
            };
            string log =
                "e: app/src/main/java/qa/A.kt:10:1 Expecting a top level declaration\n"
                + "e: app/src/main/java/qa/B.kt:3:1 Redeclaration: B\n"
                + "e: app/src/main/java/qa/C.kt:2:1 Unresolved reference: Missing\n"
                + "e: app/src/main/java/qa/D.kt:2:1 Unresolved reference: Missing\n"
                + "e: app/src/test/java/qa/ATest.kt:2:1 Unresolved reference: A\n";
            var selected = ForgeAgenticRepair.Targets(p, log);
            Equal(3, selected.Count);
            Equal("app/src/main/java/qa/A.kt", selected[0]);
            True(selected.Contains("app/src/main/java/qa/B.kt"));
            True(!selected.Any(ForgeAgenticRepair.TestPath));
        });

        await Test("Agentic test path protection covers Windows and Android tests", () =>
        {
            True(ForgeAgenticRepair.TestPath("tests/Vanta.Tests/ForgeTests.cs"));
            True(ForgeAgenticRepair.TestPath("app/src/test/java/qa/A.kt"));
            True(ForgeAgenticRepair.TestPath("app/src/androidTest/java/qa/A.kt"));
            True(!ForgeAgenticRepair.TestPath("src/Vanta.Core/ForgeExecution.cs"));
            True(!ForgeAgenticRepair.TestPath("app/src/main/java/qa/A.kt"));
        });

        await Test("Agentic diagnostic signature is stable across timing noise", () =>
        {
            string a = "error CS0103: The name X does not exist\nBUILD FAILED in 12s\n";
            string b = "error CS0103: The name X does not exist\nBUILD FAILED in 99s\n";
            Equal(ForgeAgenticRepair.DiagnosticSignature(a), ForgeAgenticRepair.DiagnosticSignature(b));
        });

        await Test("Agentic repair cap is small and explicit", () =>
        {
            Equal(3, ForgeAgenticRepair.MaxFilesPerPass);
        });
    }
}
