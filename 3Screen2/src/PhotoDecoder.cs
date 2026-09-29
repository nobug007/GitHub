using System;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;

namespace ThreeScreen;

public static class PhotoDecoder
{
    public static BitmapSource Decode(byte[] bytes)
    {
        using var stream = new MemoryStream(bytes);
        var frame = BitmapDecoder.Create(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad).Frames[0];
        ushort orientation = 1;
        if (frame.Metadata is BitmapMetadata metadata)
        {
            foreach (var query in new[] { "/app1/ifd/{ushort=274}", "/ifd/{ushort=274}" })
            {
                try
                {
                    if (metadata.GetQuery(query) is object value) { orientation = Convert.ToUInt16(value); break; }
                }
                catch (Exception ex) when (ex is NotSupportedException or ArgumentException or System.Runtime.InteropServices.COMException) { }
            }
        }
        Transform? transform = orientation switch
        {
            2 => new ScaleTransform(-1, 1),
            3 => new RotateTransform(180),
            4 => new ScaleTransform(1, -1),
            5 => new TransformGroup { Children = { new RotateTransform(90), new ScaleTransform(-1, 1) } },
            6 => new RotateTransform(90),
            7 => new TransformGroup { Children = { new RotateTransform(90), new ScaleTransform(1, -1) } },
            8 => new RotateTransform(270),
            _ => null
        };
        BitmapSource result = transform == null ? frame : new TransformedBitmap(frame, transform);
        result.Freeze(); return result;
    }
}
