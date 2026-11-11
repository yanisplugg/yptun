using Microsoft.UI.Xaml.Data;

namespace YPtun.WinUI.Converters;

public sealed class ConnectionActionConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) => value is true ? "Отключить" : "Подключить";

    public object ConvertBack(object value, Type targetType, object parameter, string language) => throw new NotSupportedException();
}
