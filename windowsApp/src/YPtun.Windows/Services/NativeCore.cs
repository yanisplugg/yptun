using System.Runtime.InteropServices;
using System.Reflection;

namespace YPtun.WinUI.Services;

internal static partial class NativeCore
{
    internal const string LibraryFileName = "yptun_windows.dll";

    private static readonly object Sync = new();
    private static nint _handle;

    static NativeCore()
    {
        NativeLibrary.SetDllImportResolver(typeof(NativeCore).Assembly, ResolveLibrary);
    }

    private static nint ResolveLibrary(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
    {
        if (!string.Equals(libraryName, LibraryFileName, StringComparison.OrdinalIgnoreCase))
        {
            return 0;
        }

        var path = Path.Combine(AppContext.BaseDirectory, "Native", LibraryFileName);
        return NativeLibrary.Load(path, assembly, searchPath);
    }

    public static void Initialize(AppPaths paths)
    {
        lock (Sync)
        {
            if (_handle != 0)
            {
                return;
            }

            _handle = yptun_windows_create(paths.DataDirectory, paths.NativeDirectory, "ru", "4.0.0");
            if (_handle == 0)
            {
                throw new InvalidOperationException("Не удалось создать Windows-контекст YPtun");
            }
        }
    }

    public static string Send(string requestJson)
    {
        lock (Sync)
        {
            if (_handle == 0)
            {
                throw new InvalidOperationException("Нативное ядро не инициализировано");
            }

            var result = yptun_windows_command(_handle, requestJson);
            if (result == 0)
            {
                throw new InvalidOperationException("Нативное ядро вернуло пустой указатель");
            }

            try
            {
                return Marshal.PtrToStringUTF8(result) ?? "";
            }
            finally
            {
                yptun_windows_string_free(result);
            }
        }
    }

    public static void Dispose()
    {
        lock (Sync)
        {
            if (_handle == 0)
            {
                return;
            }

            yptun_windows_destroy(_handle);
            _handle = 0;
        }
    }

    [LibraryImport(LibraryFileName, EntryPoint = "yptun_windows_create", StringMarshalling = StringMarshalling.Utf8)]
    private static partial nint yptun_windows_create(string appDataDirectory, string nativeDirectory, string locale, string appVersion);

    [LibraryImport(LibraryFileName, EntryPoint = "yptun_windows_command", StringMarshalling = StringMarshalling.Utf8)]
    private static partial nint yptun_windows_command(nint handle, string requestJson);

    [LibraryImport(LibraryFileName, EntryPoint = "yptun_windows_string_free")]
    private static partial void yptun_windows_string_free(nint value);

    [LibraryImport(LibraryFileName, EntryPoint = "yptun_windows_destroy")]
    private static partial void yptun_windows_destroy(nint handle);
}
