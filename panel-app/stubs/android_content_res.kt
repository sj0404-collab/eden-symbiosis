package android.content.res
// open() throws IOException for a missing asset - that is the branch
// PanelAssets has to handle, so the stub must declare it can happen.
class AssetManager { fun open(name: String): java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0)) }
