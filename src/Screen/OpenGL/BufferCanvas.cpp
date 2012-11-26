/*
Copyright_License {

  XCSoar Glide Computer - http://www.xcsoar.org/
  Copyright (C) 2000-2012 The XCSoar Project
  A detailed list of copyright holders can be found in the file "AUTHORS".

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License
  as published by the Free Software Foundation; either version 2
  of the License, or (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
}
*/

#include "Screen/BufferCanvas.hpp"
#include "Screen/OpenGL/Scope.hpp"
#include "Screen/OpenGL/Compatibility.hpp"
#include "Globals.hpp"
#include "Texture.hpp"
#include "FrameBuffer.hpp"
#include "RenderBuffer.hpp"
#include "Init.hpp"

#include <assert.h>

BufferCanvas::BufferCanvas(const Canvas &canvas,
                           UPixelScalar _width, UPixelScalar _height)
  :Canvas({_width, _height}),
   texture(new GLTexture(_width, _height))
{
  assert(canvas.IsDefined());
}

void
BufferCanvas::Create(const Canvas &canvas,
                     UPixelScalar _width, UPixelScalar _height)
{
  assert(canvas.IsDefined());
  assert(!active);

  const PixelSize new_size(_width, _height);

  Destroy();
  texture = new GLTexture(_width, _height);

  if (OpenGL::frame_buffer_object) {
    frame_buffer = new GLFrameBuffer();

    stencil_buffer = new GLRenderBuffer();
    stencil_buffer->Bind();
    PixelSize size = texture->GetAllocatedSize();
#ifdef HAVE_GLES
    stencil_buffer->Storage(GL_DEPTH24_STENCIL8_OES, size.cx, size.cy);
#else
    stencil_buffer->Storage(FBO::DEPTH_STENCIL, size.cx, size.cy);
#endif
    stencil_buffer->Unbind();
  }

  Canvas::Create(new_size);
  AddSurfaceListener(*this);
}

void
BufferCanvas::Destroy()
{
  assert(!active);

  if (IsDefined()) {
    RemoveSurfaceListener(*this);

    delete stencil_buffer;
    stencil_buffer = NULL;

    delete frame_buffer;
    frame_buffer = NULL;

    delete texture;
    texture = NULL;
  }
}

void
BufferCanvas::Resize(UPixelScalar _width, UPixelScalar _height)
{
  assert(IsDefined());

  const PixelSize new_size(_width, _height);
  if (new_size == GetSize())
    return;

  texture->ResizeDiscard(new_size);

  if (stencil_buffer != NULL) {
    stencil_buffer->Bind();
    PixelSize size = texture->GetAllocatedSize();
#ifdef HAVE_GLES
    stencil_buffer->Storage(GL_DEPTH24_STENCIL8_OES, size.cx, size.cy);
#else
    stencil_buffer->Storage(FBO::DEPTH_STENCIL, size.cx, size.cy);
#endif
    stencil_buffer->Unbind();
  }

  Canvas::Create(new_size);
}

void
BufferCanvas::Begin(Canvas &other)
{
  assert(IsDefined());
  assert(!active);

  Resize(other.GetWidth(), other.GetHeight());

  if (frame_buffer != NULL) {
    /* activate the frame buffer */
    frame_buffer->Bind();
    texture->AttachFramebuffer(FBO::COLOR_ATTACHMENT0);

    stencil_buffer->AttachFramebuffer(FBO::DEPTH_ATTACHMENT);
    stencil_buffer->AttachFramebuffer(FBO::STENCIL_ATTACHMENT);

    /* save the old viewport */
    old_translate = OpenGL::translate;
    old_size.cx = OpenGL::screen_width;
    old_size.cy = OpenGL::screen_height;
    glPushMatrix();

    /* configure a new viewport */
    OpenGL::SetupViewport(GetWidth(), GetHeight());
    OpenGL::translate = {0, 0};
  } else {
    offset = other.offset;
  }

  active = true;
}

void
BufferCanvas::Commit(Canvas &other)
{
  assert(IsDefined());
  assert(active);
  assert(GetWidth() == other.GetWidth());
  assert(GetHeight() == other.GetHeight());

  if (frame_buffer != NULL) {
    frame_buffer->Unbind();

    /* restore the old viewport */

    assert(OpenGL::translate == RasterPoint(0, 0));

    OpenGL::SetupViewport(old_size.cx, old_size.cy);

    OpenGL::translate = old_translate;

    glPopMatrix();

    /* copy frame buffer to screen */
    CopyTo(other);
  } else {
    assert(offset == other.offset);

    /* copy screen to texture */
    CopyToTexture(*texture, GetRect());
  }

  active = false;
}

void
BufferCanvas::CopyTo(Canvas &other)
{
  assert(IsDefined());
  assert(!active || frame_buffer != NULL);

  OpenGL::glTexEnvi(GL_TEXTURE_ENV, GL_TEXTURE_ENV_MODE, GL_REPLACE);

  GLEnable scope(GL_TEXTURE_2D);
  texture->Bind();
  texture->DrawFlipped(other.GetRect(), GetRect());
}

void
BufferCanvas::SurfaceCreated()
{
}

void
BufferCanvas::SurfaceDestroyed()
{
  /* discard the buffer when the Android app is suspended; it needs a
     full redraw to restore it after resuming */

  Destroy();
}
