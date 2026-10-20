#import <UIKit/UIKit.h>
NS_ASSUME_NONNULL_BEGIN
/// The native view owns a private Filament engine; never pass vault data to it.
@interface ChurSpiritView : UIView
- (instancetype)initWithModelURL:(NSURL *)url fallbackImage:(nullable UIImage *)image;
- (void)updateClip:(NSString *)clip serial:(int32_t)serial motionSerial:(int32_t)motionSerial
     reducedMotion:(BOOL)reducedMotion
            dragX:(float)dragX dragY:(float)dragY;
/// Idempotent. Required when Compose releases the surface.
- (void)dispose;
@end
NS_ASSUME_NONNULL_END
