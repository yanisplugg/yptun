using Microsoft.UI.Xaml.Data;

namespace YPtun.WinUI.Converters;

public sealed class InvertedBooleanConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, string language) => value is not true;

    public object ConvertBack(object value, Type targetType, object parameter, string language) => value is not true;
}
