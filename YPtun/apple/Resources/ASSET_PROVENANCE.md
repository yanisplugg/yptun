# Apple asset provenance

`Assets.xcassets/CatTile.imageset` is copied byte-for-byte from the shipping Apple client at
`YPtun/iosApp/iosApp/Assets.xcassets/CatTile.imageset`. It is the existing YPtun cat artwork, not a
generated replacement.

| File | SHA-256 |
| --- | --- |
| `ic_qs_tile_cat.png` | `A96DD77CD6865DC000459E57DF93C261B49E020D97468D8BA03416D1A7A7B628` |
| `ic_qs_tile_cat@2x.png` | `CEB66C8B6100E5E12A6209F2CD382AFE3044E4A58CF3CC508943BA4C1AF1D703` |
| `ic_qs_tile_cat@3x.png` | `AF8AC1E124C9849F705363E0785A0C5025F6B51E6ED61A602F7E011AA7C81A04` |

`Assets.xcassets/AppIcon.appiconset` is copied from the shipping iOS asset catalog at
`YPtun/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset`. `MacAppIcon.appiconset` is derived from
that set's `icon_ios-marketing_1024x1024_1x.png` master (SHA-256
`10257A61DA1C7A4810CF1694DCDEF2550576519760C14E41C65B0292389BA52A`) with Lanczos scaling:

```text
ffmpeg -i icon_ios-marketing_1024x1024_1x.png -vf scale=<size>:<size>:flags=lanczos icon_<size>.png
```
