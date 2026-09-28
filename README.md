Huffman File Zipper
A lossless file compression and decompression tool built in Java using Huffman Coding and LZ77. The application provides a simple Java Swing GUI and supports multiple compression strategies, including an automatic mode that selects the smallest result.

📌 Features
- 📦 Lossless file compression
- 🔓 Lossless decompression
- 🌳 Huffman Coding
- 🔄 LZ77 + Huffman compression
- 🤖 Automatic compression method selection
- 💾 Fallback to storing the file when compression does not reduce its size
- 📊 Displays:
  - Original file size
  - Compressed file size
  - Space saved
  - Compression ratio
  - Compression method used
- 📈 Real-time progress bar
- 🖥️ Java Swing graphical interface
- ⚡ Background processing using SwingWorker
- 🗂️ Supports arbitrary binary files, not just .txt
- 🛡️ Detects invalid/corrupted compressed files
- 🔄 Supports the older HUF1 format in addition to the current HUF2 format

🖥️ Application Preview
The application provides three compression modes:

Auto (pick smallest)
Huffman only
LZ77 + Huffman
