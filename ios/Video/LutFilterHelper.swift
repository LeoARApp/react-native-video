import Foundation
import UIKit
import QuartzCore
import OpenGLES
import GLKit
import AVFoundation
import MediaAccessibility


@objc open class LutFilterHelper : NSObject {
    @objc open var lutFilteredImage: CIImage!
    
    @objc
    open func setFilterCIImage(ciImage: CIImage, lut: UIImage) {
        if lut.size.width != 0 {
            let filteredImage = ciImage.applyLUTFilter(LUT: lut, volume: 1)
            self.lutFilteredImage = filteredImage;
        } else {
            self.lutFilteredImage = nil;
        }
    }
}

class Shaders {
  static let vertex =
  """
  attribute vec4 position;
  attribute vec2 a_texCoordIn;
  varying vec2 v_TexCoordOut;

  void main(void) {
      v_TexCoordOut = a_texCoordIn;
      gl_Position = position;
  }
  """
  static let fragement =
  """
  precision mediump float;

  varying vec2 v_TexCoordOut;
  uniform sampler2D inputImageTexture;
  uniform sampler2D inputImageTexture2; // lookup texture
  uniform float volume;

  void main()
  {
      vec4 textureColor = texture2D(inputImageTexture, v_TexCoordOut);
      
      float blueColor = textureColor.b * 63.0;
      
      vec2 quad1;
      quad1.y = floor(floor(blueColor) / 8.0);
      quad1.x = floor(blueColor) - (quad1.y * 8.0);
      
      vec2 quad2;
      quad2.y = floor(ceil(blueColor) / 8.0);
      quad2.x = ceil(blueColor) - (quad2.y * 8.0);
      
      vec2 texPos1;
      texPos1.x = (quad1.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.r);
      texPos1.y = (quad1.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.g);
      
      vec2 texPos2;
      texPos2.x = (quad2.x * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.r);
      texPos2.y = (quad2.y * 0.125) + 0.5/512.0 + ((0.125 - 1.0/512.0) * textureColor.g);
      
      vec4 newColor1 = texture2D(inputImageTexture2, texPos1);
      vec4 newColor2 = texture2D(inputImageTexture2, texPos2);
      
      vec4 newColor = mix(newColor1, newColor2, fract(blueColor));
      gl_FragColor = mix(textureColor, vec4(newColor.rgb, textureColor.w), volume);
  }
  """
}


private var vertices: [GLfloat] = [
  -1, -1, 0,
  1, -1, 0,
  -1, 1, 0,
  1, 1, 0
]

private var coords: [GLfloat] = [
  0, 0,
  1, 0,
  0, 1,
  1, 1
]


//Extensions to pass arguments to GL land
extension Array {
  func size () -> Int {
    return self.count * MemoryLayout.size(ofValue: self[0])
  }
}

extension Int32 {
  func __conversion() -> GLenum {
    return GLuint(self)
  }
  
  func __conversion() -> GLboolean {
    return GLboolean(UInt8(self))
  }
}

extension Int {
  func __conversion() -> Int32 {
    return Int32(self)
  }
  
  func __conversion() -> GLubyte {
    return GLubyte(self)
  }
  
}

public final class LUTFilter {
  private var context: EAGLContext!
  private var positionSlot: GLuint = GLuint()
  private var colorSlot: GLuint = GLuint()
  private var programHandle: GLuint = GLuint()
  private var offscreenFrameBuffer: GLuint = GLuint()
  private var image: CIImage!
  private var volume: Float = 1.0
  private let width = 1080.0
  private let height = 1920.0
  private var shouldUseNotFilteredImage = false
    
  public init(image: CIImage) {
    self.image = image
    setupContext()
    setUpOffScreenFrameBuffer(image: image)
    compileShaders()
  }
  
  internal func filteredImage(LUT: UIImage?, volume: Float = 1.0) -> CIImage {
    self.volume = min(1.0, max(volume, 0.0))
      
    if(shouldUseNotFilteredImage) {
        return image;
    }
      
    if let lutImage = LUT {
      setTexture(image: image, LUTImage: lutImage)
      renderToOFB()
      return getImageFromBuffer(width: Int(width), height: Int(height))
    } else {
      return image
    }
  }
  
  private func setupContext() {
    // Just like with CoreGraphics, in order to do much with OpenGL, we need a context.
    //   Here we create a new context with the version of the rendering API we want and
    //   tells OpenGL that when we draw, we want to do so within this context.
    let api: EAGLRenderingAPI = EAGLRenderingAPI.openGLES2
    self.context = EAGLContext(api: api)
      
    if (self.context == nil) {
      print("Failed to initialize OpenGLES 2.0 context!")
      self.shouldUseNotFilteredImage = true;
    }
      
    if (!EAGLContext.setCurrent(self.context)) {
      print("Failed to set current OpenGL context!")
      self.shouldUseNotFilteredImage = true;
    }
  }
  private func setUpOffScreenFrameBuffer(image: CIImage) {
    glGenFramebuffers(1, &offscreenFrameBuffer)
    glBindFramebuffer(GLenum(GL_FRAMEBUFFER), offscreenFrameBuffer)
    
    var texture = GLuint()
    glGenTextures(1, &texture)
    glBindTexture(GLenum(GL_TEXTURE_2D), texture)
    glTexImage2D(GLenum(GL_TEXTURE_2D), 0, GL_RGBA, GLsizei(width), GLsizei(height), 0, GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), nil)
  
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_MIN_FILTER), GLfloat(GL_LINEAR))
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_MAG_FILTER), GLfloat(GL_LINEAR))
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_WRAP_S), GLfloat(GL_CLAMP_TO_EDGE))
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_WRAP_T), GLfloat(GL_CLAMP_TO_EDGE))
    
    glFramebufferTexture2D(GLenum(GL_FRAMEBUFFER), GLenum(GL_COLOR_ATTACHMENT0), GLenum(GL_TEXTURE_2D), texture, 0)
    
    let stauts = glCheckFramebufferStatus(GLenum(GL_FRAMEBUFFER))
    if stauts != GLenum(GL_FRAMEBUFFER_COMPLETE) {
      print("failed to make complete framebuffer object \(stauts)")
    }
    glBindFramebuffer(GLenum(GL_FRAMEBUFFER), 0)
    glBindTexture(GLenum(GL_TEXTURE_2D), 0)
    
    glViewport(0, 0, GLsizei(width), GLsizei(height))
    
  }
  
  private func compileShader(shaderString: String, shaderType: GLenum) -> GLuint {
    
    // Tell OpenGL to create an OpenGL object to represent the shader, indicating if it's a vertex or a fragment shader.
    let shaderHandle: GLuint = glCreateShader(shaderType)
    
    if shaderHandle == 0 {
      NSLog("Couldn't create shader")
    }
    
    // Conver shader string to CString and call glShaderSource to give OpenGL the source for the shader.
    var shaderStringUTF8 = (shaderString as NSString).utf8String
    var shaderStringLength: GLint = GLint(Int32((shaderString as NSString).length))
    glShaderSource(shaderHandle, 1, &shaderStringUTF8, &shaderStringLength)
    
    // Tell OpenGL to compile the shader.
    glCompileShader(shaderHandle)
    
    // But compiling can fail! If we have errors in our GLSL code, we can here and output any errors.
    var compileSuccess: GLint = GLint()
    glGetShaderiv(shaderHandle, GLenum(GL_COMPILE_STATUS), &compileSuccess)
    if (compileSuccess == GL_FALSE) {
      print("Failed to compile shader!")
      self.shouldUseNotFilteredImage = true;
    }
    
    return shaderHandle
  }
  
  private func compileShaders() {
    
    // Compile our vertex and fragment shaders.
    let vertexShader: GLuint = self.compileShader(shaderString: Shaders.vertex, shaderType: GLenum(GL_VERTEX_SHADER))
    let fragmentShader: GLuint = self.compileShader(shaderString: Shaders.fragement, shaderType: GLenum(GL_FRAGMENT_SHADER))
    
    // Call glCreateProgram, glAttachShader, and glLinkProgram to link the vertex and fragment shaders into a complete program.
    programHandle = glCreateProgram()
    glAttachShader(programHandle, vertexShader)
    glAttachShader(programHandle, fragmentShader)
    glLinkProgram(programHandle)
    
    // Check for any errors.
    var linkSuccess: GLint = GLint()
    glGetProgramiv(programHandle, GLenum(GL_LINK_STATUS), &linkSuccess)
    if (linkSuccess == GL_FALSE) {
      print("Failed to create shader program!")
      self.shouldUseNotFilteredImage = true;
    }

    // Call glUseProgram to tell OpenGL to actually use this program when given vertex info.
    glUseProgram(programHandle)
    glDeleteShader(vertexShader)
    glDeleteShader(fragmentShader)
    // Finally, call glGetAttribLocation to get a pointer to the input values for the vertex shader, so we
    //  can set them in code. Also call glEnableVertexAttribArray to enable use of these arrays (they are disabled by default).
    self.positionSlot = GLuint(glGetAttribLocation(programHandle, "position"))
    self.colorSlot = GLuint(glGetAttribLocation(programHandle, "a_texCoordIn"))
    glVertexAttribPointer(self.positionSlot, 3, GLenum(GL_FLOAT), GLboolean(GL_FALSE), 0, vertices)
    glVertexAttribPointer(self.colorSlot, 2, GLenum(GL_FLOAT), GLboolean(GL_FALSE), 0, coords)
    glEnableVertexAttribArray(self.positionSlot)
    glEnableVertexAttribArray(self.colorSlot)
  }
  
  private func setTexture(image: CIImage, LUTImage: UIImage) {
    glActiveTexture(GLenum(GL_TEXTURE0))
    let textName = getTextureFromImage(sourceImage: image, translate: true)
    glBindTexture(GLenum(GL_TEXTURE_2D), textName)
    glUniform1i(glGetUniformLocation(programHandle,"inputImageTexture"), 0)
    
    glActiveTexture(GLenum(GL_TEXTURE1))
      let LUTName = getTextureFromImage(sourceImage:CIImage(image: LUTImage)!, translate: false)
    glBindTexture(GLenum(GL_TEXTURE_2D), LUTName)
    glUniform1i(glGetUniformLocation(programHandle, "inputImageTexture2"), 1)
    
    glUniform1f(glGetUniformLocation(programHandle, "volume"), self.volume)
  }
  
  private func renderToOFB() {
    glBindFramebuffer(GLenum(GL_FRAMEBUFFER), offscreenFrameBuffer)
    glDrawArrays(GLenum(GL_TRIANGLE_STRIP), 0, 4)
  }
    
  private func convertCIImageToCGImage(inputImage: CIImage) -> CGImage? {
        let context = CIContext(options: nil)
        if let cgImage = context.createCGImage(inputImage, from: inputImage.extent) {
            return cgImage
        }
        return nil
  }
  
  private func getTextureFromImage(sourceImage: CIImage, translate: Bool) -> GLuint {
          
    guard let textureImage = convertCIImageToCGImage(inputImage: sourceImage) else {
      print("Failed to load image!")
      return 0
    }
      
    let width = textureImage.width
    let height = textureImage.height
    let rect = CGRect(x: 0, y: 0, width: width, height: height)
    /*
     it will write one byte each for red, green, blue, and alpha – so 4 bytes in total.
     */
    
    let textureData = calloc(width * height * 4, MemoryLayout<GLubyte>.size) //4 components per pixel (RGBA)
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bytesPerPixel = 4
    let bytesPerRow = bytesPerPixel * width
    let bitsPerComponent = 8
    let bitmapInfo = CGImageAlphaInfo.premultipliedLast.rawValue|CGBitmapInfo.byteOrder32Big.rawValue
    let spriteContext = CGContext(data: textureData,
                                  width: width,
                                  height: height,
                                  bitsPerComponent: bitsPerComponent,
                                  bytesPerRow: bytesPerRow,
                                  space: colorSpace,
                                  bitmapInfo: bitmapInfo)
    if translate {
      spriteContext?.translateBy(x: 0, y: CGFloat(height))
      spriteContext?.scaleBy(x: 1, y: -1)
    }
    spriteContext?.clear(rect)
    spriteContext?.draw(textureImage, in: rect)
    
    glEnable(GLenum(GL_TEXTURE_2D))
    
    var textName = GLuint()
    glGenTextures(1, &textName)
    glBindTexture(GLenum(GL_TEXTURE_2D), textName)
    
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_MIN_FILTER), GLfloat(GL_LINEAR));
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_MAG_FILTER), GLfloat(GL_LINEAR));
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_WRAP_S), GLfloat(GL_CLAMP_TO_EDGE));
    glTexParameterf(GLenum(GL_TEXTURE_2D), GLenum(GL_TEXTURE_WRAP_T), GLfloat(GL_CLAMP_TO_EDGE));
    
    glTexImage2D(GLenum(GL_TEXTURE_2D), 0, GL_RGBA, GLsizei(width),
                 GLsizei(height), 0, GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), textureData)
    glBindTexture(GLenum(GL_TEXTURE_2D), 0)
    free(textureData)
    
    return textName
  }
  
  private func getImageFromBuffer(width: Int, height: Int) -> CIImage {
    let x: GLint = 0
    let y: GLint = 0
    let dataLength = width * height * 4
    let data = UnsafeMutablePointer<GLubyte>.allocate(capacity: dataLength * MemoryLayout<GLubyte>.size)
    
    glPixelStorei(GLenum(GL_PACK_ALIGNMENT), 4)
    glReadPixels(x, y, GLsizei(width), GLsizei(height), GLenum(GL_RGBA), GLenum(GL_UNSIGNED_BYTE), data)
    
    let ref = CGDataProvider(dataInfo: nil, data: data, size: dataLength, releaseData: {_,_,_ in
    })
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo(rawValue: (CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue) as UInt32)
    let iref = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4, space: colorSpace, bitmapInfo: bitmapInfo, provider: ref!, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    
    UIGraphicsBeginImageContext(CGSize(width: width, height: height))
    let cgcontext = UIGraphicsGetCurrentContext()
    cgcontext?.setBlendMode(.copy)
    cgcontext?.draw(iref!, in: CGRect(x: 0, y: 0, width: width, height: height))
    let image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    free(data)
    let ciimage = CIImage(image: image!)
    
    return ciimage!
  }
}

extension CIImage {
  public func applyLUTFilter(LUT: UIImage?, volume: Float) -> CIImage {
    return LUTFilter(image: self).filteredImage(LUT: LUT, volume: volume)
  }
}



