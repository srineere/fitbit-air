import 'package:flutter/material.dart';

ThemeData airRelayTheme(Brightness brightness) {
  final colors = ColorScheme.fromSeed(
    seedColor: const Color(0xff176b61),
    brightness: brightness,
  );
  return ThemeData(
    useMaterial3: true,
    colorScheme: colors,
    scaffoldBackgroundColor: brightness == Brightness.light
        ? const Color(0xfff6f8f6)
        : const Color(0xff101917),
    appBarTheme: AppBarTheme(
      centerTitle: false,
      backgroundColor: Colors.transparent,
      surfaceTintColor: Colors.transparent,
      titleTextStyle: TextStyle(
        fontFamily: 'Roboto',
        color: colors.onSurface,
        fontSize: 22,
        fontWeight: FontWeight.w700,
      ),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      margin: EdgeInsets.zero,
      color: colors.surface,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(24),
        side: BorderSide(color: colors.outlineVariant.withValues(alpha: .5)),
      ),
      clipBehavior: Clip.antiAlias,
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        textStyle: const TextStyle(
          fontFamily: 'Roboto',
          fontSize: 15,
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 48),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
      ),
    ),
    expansionTileTheme: const ExpansionTileThemeData(
      tilePadding: EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      shape: Border(),
      collapsedShape: Border(),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: colors.surfaceContainerLow,
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(16)),
    ),
    navigationBarTheme: NavigationBarThemeData(
      elevation: 0,
      backgroundColor: colors.surface,
      indicatorColor: colors.primaryContainer,
    ),
  );
}

/// Original mark: a wearable with a pulse, matching the Android launcher icon.
class AirRelayMark extends StatelessWidget {
  const AirRelayMark({super.key, this.size = 40});
  final double size;

  @override
  Widget build(BuildContext context) => ExcludeSemantics(
    child: SizedBox.square(
      dimension: size,
      child: CustomPaint(painter: _MarkPainter()),
    ),
  );
}

class _MarkPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 108, size.height / 108);
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(0, 0, 108, 108),
        const Radius.circular(28),
      ),
      Paint()..color = const Color(0xff123c36),
    );
    final stroke = Paint()
      ..color = const Color(0xffa8efd3)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 5
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        const Rect.fromLTWH(38, 25, 32, 58),
        const Radius.circular(14),
      ),
      stroke,
    );
    canvas.drawPath(
      Path()
        ..moveTo(43, 55)
        ..lineTo(49, 55)
        ..lineTo(53, 46)
        ..lineTo(58, 63)
        ..lineTo(62, 55)
        ..lineTo(66, 55),
      stroke
        ..color = Colors.white
        ..strokeWidth = 3.5,
    );
    canvas.drawPath(
      Path()
        ..moveTo(29, 45)
        ..quadraticBezierTo(23, 54, 29, 63)
        ..moveTo(79, 45)
        ..quadraticBezierTo(85, 54, 79, 63),
      stroke
        ..color = const Color(0xffa8efd3)
        ..strokeWidth = 4,
    );
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => false;
}
